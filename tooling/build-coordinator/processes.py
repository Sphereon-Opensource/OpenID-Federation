"""Owned build trees, host admission information, and OS-held lane locks.

Inspection itself never terminates processes, and unavailable inspection raises
RuntimeError so callers must defer admission. The one exception is the opt-out
Gradle daemon reaper, which asks daemons that Gradle reports as idle to shut down;
it never touches a busy daemon, a coordinator-owned process, or an unrelated build.
Windows jobs are assigned before child execution.
"""
from __future__ import annotations

import ctypes
import errno
import fnmatch
import gradle_registry
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time

_DRAIN_TIMEOUT = 10.0

if os.name == "nt":
    import _winapi
    import msvcrt
    from ctypes import wintypes as w

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)

    def _api(name, result, *args):
        fn = getattr(kernel, name)
        fn.restype = result
        fn.argtypes = args
        return fn

    _close = _api("CloseHandle", w.BOOL, w.HANDLE)
    _create_job = _api("CreateJobObjectW", w.HANDLE, ctypes.c_void_p, w.LPCWSTR)
    _set_job = _api("SetInformationJobObject", w.BOOL, w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD)
    _assign_job = _api("AssignProcessToJobObject", w.BOOL, w.HANDLE, w.HANDLE)
    _terminate_job = _api("TerminateJobObject", w.BOOL, w.HANDLE, w.UINT)
    _query_job = _api("QueryInformationJobObject", w.BOOL, w.HANDLE, ctypes.c_int,
                      ctypes.c_void_p, w.DWORD, ctypes.c_void_p)
    _resume = _api("ResumeThread", w.DWORD, w.HANDLE)
    _create_file = _api("CreateFileW", w.HANDLE, w.LPCWSTR, w.DWORD, w.DWORD, ctypes.c_void_p, w.DWORD, w.DWORD, w.HANDLE)

    class _BasicLimits(ctypes.Structure):
        _fields_ = [("PerProcessUserTimeLimit", ctypes.c_longlong), ("PerJobUserTimeLimit", ctypes.c_longlong),
                    ("LimitFlags", w.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                    ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", w.DWORD),
                    ("Affinity", ctypes.c_size_t), ("PriorityClass", w.DWORD), ("SchedulingClass", w.DWORD)]

    class _IoCounters(ctypes.Structure):
        _fields_ = [(name, ctypes.c_ulonglong) for name in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount", "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

    class _ExtendedLimits(ctypes.Structure):
        _fields_ = [("BasicLimitInformation", _BasicLimits), ("IoInfo", _IoCounters),
                    ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                    ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t)]

    class _JobAccounting(ctypes.Structure):
        _fields_ = [(name, ctypes.c_longlong) for name in
                    ("TotalUserTime", "TotalKernelTime", "ThisPeriodTotalUserTime", "ThisPeriodTotalKernelTime")] + [
                    (name, w.DWORD) for name in
                    ("TotalPageFaultCount", "TotalProcesses", "ActiveProcesses", "TotalTerminatedProcesses")]


class OwnedProcess:
    """An owned tree; cancel/close return only after containment has drained.

    A timeout retains ownership handles and raises TimeoutError. The caller must
    keep the lane blocked until a later close succeeds. POSIX containment uses
    process groups and conservatively waits for the group to disappear.
    """

    def __init__(self, argv, cwd, env, log_path):
        if not argv:
            raise ValueError("argv cannot be empty")
        self._closed = False
        self._returncode = None
        self._job = None
        self._handle = None
        self._proc = None
        self.pid = None
        Path(log_path).parent.mkdir(parents=True, exist_ok=True)
        self._log = open(log_path, "ab", buffering=0)
        try:
            if os.name == "nt":
                self._start_windows([os.fspath(arg) for arg in argv], cwd, env)
            else:
                self._proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                              stdout=self._log, stderr=subprocess.STDOUT, start_new_session=True)
                self.pid = self._proc.pid
        except BaseException:
            self.close()
            raise

    def _start_windows(self, argv, cwd, env):
        self._job = _create_job(None, None)
        if not self._job:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = _ExtendedLimits()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not _set_job(self._job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            raise ctypes.WinError(ctypes.get_last_error())
        thread = None
        duplicates = []
        with open(os.devnull, "rb") as null:
            try:
                # Only these temporary duplicates may be inherited by this child.
                current = _winapi.GetCurrentProcess()
                for stream in (null, self._log):
                    duplicates.append(_winapi.DuplicateHandle(current, msvcrt.get_osfhandle(stream.fileno()),
                                                              current, 0, True, _winapi.DUPLICATE_SAME_ACCESS))
                startup = subprocess.STARTUPINFO()
                startup.dwFlags = subprocess.STARTF_USESTDHANDLES
                startup.hStdInput = duplicates[0]
                startup.hStdOutput = startup.hStdError = duplicates[1]
                startup.lpAttributeList = {"handle_list": duplicates}
                self._handle, thread, self.pid, _ = _winapi.CreateProcess(
                    None, subprocess.list2cmdline(argv), None, None, True,
                    0x00000004 | subprocess.CREATE_NO_WINDOW, env, os.fspath(cwd), startup)
                if not _assign_job(self._job, self._handle):
                    raise ctypes.WinError(ctypes.get_last_error())
                if _resume(thread) == 0xFFFFFFFF:
                    raise ctypes.WinError(ctypes.get_last_error())
            except BaseException:
                if self._handle:
                    _winapi.TerminateProcess(self._handle, 1)
                    self._wait_root(time.monotonic() + _DRAIN_TIMEOUT)
                raise
            finally:
                if thread:
                    _close(thread)
                for handle in duplicates:
                    _close(handle)

    def poll(self):
        if self._returncode is not None or self._closed:
            return self._returncode
        if os.name == "nt":
            if self._handle and _winapi.WaitForSingleObject(self._handle, 0) == 0:
                self._returncode = _winapi.GetExitCodeProcess(self._handle)
        elif self._proc:
            self._returncode = self._proc.poll()
        return self._returncode

    def cancel(self):
        if self._closed:
            return
        deadline = time.monotonic() + _DRAIN_TIMEOUT
        if os.name == "nt":
            if self._job and not _terminate_job(self._job, 1):
                raise ctypes.WinError(ctypes.get_last_error())
            self._wait_root(deadline)
            while self._job:
                accounting = _JobAccounting()
                if not _query_job(self._job, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                    raise ctypes.WinError(ctypes.get_last_error())
                if accounting.ActiveProcesses == 0:
                    break
                self._pause_for_drain(deadline)
        elif self._proc:
            try:
                os.killpg(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                self._proc.wait(timeout=max(0, deadline - time.monotonic()))
            except subprocess.TimeoutExpired as exc:
                raise TimeoutError("owned process did not terminate; lane must remain blocked") from exc
            while True:
                try:
                    os.killpg(self.pid, 0)
                except ProcessLookupError:
                    break
                self._pause_for_drain(deadline)
        self.poll()

    def _wait_root(self, deadline):
        if self._handle:
            result = _winapi.WaitForSingleObject(self._handle, max(0, int((deadline - time.monotonic()) * 1000)))
            if result == 258:  # WAIT_TIMEOUT
                raise TimeoutError("owned process did not terminate; lane must remain blocked")
            if result != 0:
                raise RuntimeError("owned process termination could not be confirmed; lane must remain blocked")

    @staticmethod
    def _pause_for_drain(deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("owned process tree did not drain; lane must remain blocked")
        time.sleep(min(.02, remaining))

    def close(self):
        if self._closed:
            return
        self.poll()
        self.cancel()
        # Do not relinquish containment or mark closed when drainage failed.
        if os.name == "nt":
            if self._job:
                _close(self._job)
                self._job = None
            if self._handle:
                _close(self._handle)
                self._handle = None
        self._log.close()
        self._closed = True

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class ExclusiveLock:
    """Nonblocking exclusive lock; keep the OS handle open for its lifetime.

    The lock file persists. Removing it can break exclusion on POSIX. On Windows
    FileShare.None makes this compatible with existing PowerShell lane locks.
    """

    def __init__(self, path):
        self.path = Path(path)
        self._handle = None

    def __enter__(self):
        if self._handle is not None:
            raise RuntimeError("lock is already held by this instance")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if os.name == "nt":
            handle = _create_file(str(self.path.absolute()), 0xC0000000, 0, None, 4, 0x80, None)
            if handle == ctypes.c_void_p(-1).value:
                error = ctypes.get_last_error()
                if error in (32, 33):
                    raise BlockingIOError(errno.EWOULDBLOCK, "lane lock is busy", str(self.path))
                raise ctypes.WinError(error)
            self._handle = handle
        else:
            import fcntl
            stream = open(self.path, "a+b")
            try:
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BaseException:
                stream.close()
                raise
            self._handle = stream
        return self

    def close(self):
        if self._handle is not None:
            if os.name == "nt":
                _close(self._handle)
            else:
                self._handle.close()
            self._handle = None

    def __exit__(self, *exc):
        self.close()


def pid_alive(pid) -> bool:
    """Return True if the given process ID is currently running on the host."""
    try:
        pid = int(pid)
    except (ValueError, TypeError):
        return False
    if pid <= 0:
        return False
    if os.name == "nt":
        open_proc = _api("OpenProcess", w.HANDLE, w.DWORD, w.BOOL, w.DWORD)
        handle = open_proc(0x1000, False, pid)
        if handle:
            _close(handle)
            return True
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False


def boot_time() -> float | None:
    """Return the host boot time as epoch seconds, or None when it cannot be read.

    Every process that existed before this instant is gone, so a job started
    earlier has no surviving process tree. Windows Fast Startup resumes a
    hibernated kernel without resetting this value; that only keeps older jobs
    on the strict external-process path.
    """
    try:
        if os.name == "nt":
            ticks = _api("GetTickCount64", ctypes.c_ulonglong)()
            return time.time() - ticks / 1000.0
        with open("/proc/stat", encoding="ascii") as stat:
            for line in stat:
                if line.startswith("btime "):
                    return float(line.split()[1])
    except (OSError, ValueError, AttributeError):
        pass
    return None


def started_before_boot(job, boot=None) -> bool:
    """True when the job's execution provably began before the current boot."""
    boot = boot_time() if boot is None else boot
    started = job.get("started") or job.get("created")
    return boot is not None and isinstance(started, (int, float)) and started < boot


def get_memory_status():
    """Return dictionary of physical memory and swap stats in GiB."""
    if os.name == "nt":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", w.DWORD), ("load", w.DWORD)] + [(field, ctypes.c_ulonglong) for field in
                       ("totalPhys", "availPhys", "totalPage", "availPage", "totalVirtual", "availVirtual", "availExtended")]
        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if not _api("GlobalMemoryStatusEx", w.BOOL, ctypes.c_void_p)(ctypes.byref(status)):
            raise ctypes.WinError(ctypes.get_last_error())
        total_ram = status.totalPhys / 2**30
        avail_ram = status.availPhys / 2**30
        # totalPage/availPage are the system commit limit and the commit charge
        # still available under it, not pagefile sizes. Windows refuses an
        # allocation once the commit limit is reached even when RAM is free.
        total_page = status.totalPage / 2**30
        avail_page = status.availPage / 2**30
        swap_total = max(0.0, total_page - total_ram)
        swap_free = min(swap_total, max(0.0, avail_page - avail_ram))
        return {
            "ram_total_gb": round(total_ram, 2),
            "ram_used_gb": round(max(0.0, total_ram - avail_ram), 2),
            "ram_free_gb": round(avail_ram, 2),
            "ram_available_gb": round(avail_ram, 2),
            "buff_cache_gb": 0.0,
            "swap_total_gb": round(swap_total, 2),
            "swap_used_gb": round(max(0.0, swap_total - swap_free), 2),
            "swap_free_gb": round(swap_free, 2),
            "commit_limit_gb": round(total_page, 2),
            "commit_available_gb": round(avail_page, 2),
        }
    try:
        meminfo = {}
        with open("/proc/meminfo", "r", encoding="utf-8") as stream:
            for line in stream:
                parts = line.split(":")
                if len(parts) == 2:
                    k = parts[0].strip()
                    val = parts[1].strip().split()[0]
                    try:
                        meminfo[k] = int(val) * 1024
                    except ValueError:
                        pass
        total_ram = meminfo.get("MemTotal", 0)
        free_ram = meminfo.get("MemFree", 0)
        buffers = meminfo.get("Buffers", 0)
        cached = meminfo.get("Cached", 0)
        avail_ram = meminfo.get("MemAvailable", free_ram + buffers + cached)
        swap_total = meminfo.get("SwapTotal", 0)
        swap_free = meminfo.get("SwapFree", 0)
        return {
            "ram_total_gb": round(total_ram / 2**30, 2),
            "ram_used_gb": round(max(0.0, total_ram - avail_ram) / 2**30, 2),
            "ram_free_gb": round(free_ram / 2**30, 2),
            "ram_available_gb": round(avail_ram / 2**30, 2),
            "buff_cache_gb": round((buffers + cached) / 2**30, 2),
            "swap_total_gb": round(swap_total / 2**30, 2),
            "swap_used_gb": round(max(0.0, swap_total - swap_free) / 2**30, 2),
            "swap_free_gb": round(swap_free / 2**30, 2),
        }
    except (OSError, FileNotFoundError, ValueError):
        page_size = os.sysconf("SC_PAGE_SIZE")
        total_pages = os.sysconf("SC_PHYS_PAGES")
        avail_pages = os.sysconf("SC_AVPHYS_PAGES")
        total_ram = (total_pages * page_size) / 2**30
        avail_ram = (avail_pages * page_size) / 2**30
        return {
            "ram_total_gb": round(total_ram, 2),
            "ram_used_gb": round(max(0.0, total_ram - avail_ram), 2),
            "ram_free_gb": round(avail_ram, 2),
            "ram_available_gb": round(avail_ram, 2),
            "buff_cache_gb": 0.0,
            "swap_total_gb": 0.0,
            "swap_used_gb": 0.0,
            "swap_free_gb": 0.0,
        }


def available_memory_gb(include_swap=False, swap_weight=1.0):
    """Currently available memory in GiB.

    If include_swap is True, adds (swap_free_gb * swap_weight) to available RAM.
    Where the host reports remaining commit charge (Windows), the result never
    exceeds it: free RAM cannot be allocated once the commit limit is reached.
    """
    stats = get_memory_status()
    available = stats["ram_available_gb"]
    if include_swap and stats.get("swap_free_gb", 0) > 0:
        available += stats["swap_free_gb"] * max(0.0, min(1.0, float(swap_weight)))
    commit_available = stats.get("commit_available_gb")
    if commit_available is not None:
        available = min(available, commit_available)
    return round(available, 2)


def _parent_map():
    """pid -> parent pid for every process on the host."""
    if os.name == "nt":
        class Entry(ctypes.Structure):
            _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("pid", w.DWORD), ("heap", ctypes.c_size_t),
                        ("module", w.DWORD), ("threads", w.DWORD), ("parent", w.DWORD),
                        ("priority", w.LONG), ("flags", w.DWORD), ("exe", w.WCHAR * 260)]
        snapshot = _api("CreateToolhelp32Snapshot", w.HANDLE, w.DWORD, w.DWORD)(2, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        first = _api("Process32FirstW", w.BOOL, w.HANDLE, ctypes.POINTER(Entry))
        following = _api("Process32NextW", w.BOOL, w.HANDLE, ctypes.POINTER(Entry))
        parents = {}
        try:
            entry = Entry()
            entry.size = ctypes.sizeof(entry)
            ok = first(snapshot, ctypes.byref(entry))
            while ok:
                parents[entry.pid] = entry.parent
                ok = following(snapshot, ctypes.byref(entry))
        finally:
            _close(snapshot)
        return parents
    parents = {}
    for stat in Path("/proc").glob("[0-9]*/stat"):
        try:
            text = stat.read_text()
            parents[int(stat.parent.name)] = int(text[text.rindex(")") + 2:].split()[1])
        except (OSError, ValueError, IndexError):
            continue
    return parents


def _process_commit_bytes(pid):
    """Private committed memory of one process (Windows PrivateUsage, Linux RSS+swap)."""
    if os.name == "nt":
        class Counters(ctypes.Structure):
            _fields_ = [("cb", w.DWORD), ("PageFaultCount", w.DWORD)] + [
                (name, ctypes.c_size_t) for name in (
                    "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                    "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage",
                    "PeakPagefileUsage", "PrivateUsage")]
        handle = _api("OpenProcess", w.HANDLE, w.DWORD, w.BOOL, w.DWORD)(0x1000, False, pid)
        if not handle:
            return 0
        try:
            counters = Counters()
            counters.cb = ctypes.sizeof(counters)
            info = _api("K32GetProcessMemoryInfo", w.BOOL, w.HANDLE, ctypes.c_void_p, w.DWORD)
            return counters.PrivateUsage if info(handle, ctypes.byref(counters), counters.cb) else 0
        finally:
            _close(handle)
    total = 0
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith(("VmRSS:", "VmSwap:")):
                total += int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return 0
    return total


def process_tree_memory_gb(root_pids):
    """Committed memory of each root's whole process tree, in GiB.

    Used to charge running builds only for the part of their reservation they
    have not yet used, since the rest is already missing from available memory.
    """
    roots = {int(pid) for pid in root_pids if isinstance(pid, int) and pid > 0}
    if not roots:
        return {}
    children = {}
    for pid, parent in _parent_map().items():
        children.setdefault(parent, []).append(pid)
    result = {}
    for root in roots:
        seen, stack, total = set(), [root], 0
        while stack:
            pid = stack.pop()
            if pid in seen:
                continue
            seen.add(pid)
            total += _process_commit_bytes(pid)
            stack.extend(children.get(pid, ()))
        result[root] = total / 1024 ** 3
    return result


_cpu_sample = None


def windows_cpu_load():
    """Approximate a 1-minute load average on Windows from system CPU times.

    Busy fraction since the previous call times the core count, the same
    scale as os.getloadavg(). The first call samples over half a second.
    """
    class FileTime(ctypes.Structure):
        _fields_ = [("low", w.DWORD), ("high", w.DWORD)]
    get_times = _api("GetSystemTimes", w.BOOL, ctypes.POINTER(FileTime), ctypes.POINTER(FileTime), ctypes.POINTER(FileTime))

    def sample():
        idle, kernel, user = FileTime(), FileTime(), FileTime()
        if not get_times(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
            raise ctypes.WinError(ctypes.get_last_error())
        value = lambda t: (t.high << 32) | t.low
        return time.monotonic(), value(idle), value(kernel) + value(user)  # kernel time includes idle

    global _cpu_sample
    current = sample()
    previous = _cpu_sample
    if previous is None or current[0] - previous[0] > 60 or current[0] - previous[0] < 0.2:
        time.sleep(0.5)
        previous, current = current, sample()
    _cpu_sample = current
    total = current[2] - previous[2]
    if total <= 0:
        return None
    busy = 1.0 - (current[1] - previous[1]) / total
    return max(0.0, min(1.0, busy)) * (os.cpu_count() or 1)


def _candidate(name):
    stem = str(name).lower().removesuffix(".exe")
    return stem in ("java", "javaw") or "native-image" in stem or "kotlin" in stem


def _inspect_windows():
    class Entry(ctypes.Structure):
        _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("pid", w.DWORD), ("heap", ctypes.c_size_t),
                    ("module", w.DWORD), ("threads", w.DWORD), ("parent", w.DWORD),
                    ("priority", w.LONG), ("flags", w.DWORD), ("exe", w.WCHAR * 260)]
    snapshot = _api("CreateToolhelp32Snapshot", w.HANDLE, w.DWORD, w.DWORD)(2, 0)
    if snapshot == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    first = _api("Process32FirstW", w.BOOL, w.HANDLE, ctypes.POINTER(Entry))
    following = _api("Process32NextW", w.BOOL, w.HANDLE, ctypes.POINTER(Entry))
    rows = []
    try:
        entry = Entry()
        entry.size = ctypes.sizeof(entry)
        ok = first(snapshot, ctypes.byref(entry))
        while ok:
            rows.append({"pid": entry.pid, "parent": entry.parent, "name": entry.exe, "command": ""})
            if len(rows) > 100000:
                raise RuntimeError("process inspection limit exceeded")
            ok = following(snapshot, ctypes.byref(entry))
        if ctypes.get_last_error() != 18:  # ERROR_NO_MORE_FILES
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        _close(snapshot)
    # Kernel command-line queries avoid WMI service hangs and external helpers.
    ntdll = ctypes.WinDLL("ntdll")
    query = ntdll.NtQueryInformationProcess
    query.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.ULONG, ctypes.POINTER(w.ULONG)]
    query.restype = w.LONG
    open_process = _api("OpenProcess", w.HANDLE, w.DWORD, w.BOOL, w.DWORD)
    class UnicodeString(ctypes.Structure):
        _fields_ = [("length", w.USHORT), ("maximum", w.USHORT), ("buffer", ctypes.c_void_p)]
    for row in rows:
        if not _candidate(row["name"]):
            continue
        handle = open_process(0x1000, False, row["pid"])
        if not handle:
            if ctypes.get_last_error() == 87:  # exited since snapshot
                continue
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            required = w.ULONG()
            query(handle, 60, None, 0, ctypes.byref(required))
            if not 0 < required.value <= 1024 * 1024:
                raise RuntimeError("cannot inspect build candidate command line")
            buffer = ctypes.create_string_buffer(required.value)
            if query(handle, 60, buffer, required.value, ctypes.byref(required)) < 0:
                raise RuntimeError("cannot inspect build candidate command line")
            value = UnicodeString.from_buffer(buffer)
            start = ctypes.addressof(buffer)
            if not value.buffer or not start <= value.buffer <= start + len(buffer) - value.length:
                raise RuntimeError("invalid process command-line response")
            row["command"] = ctypes.wstring_at(value.buffer, value.length // 2)
        finally:
            _close(handle)
    return rows


def _inspect_processes():
    command = ([sys.executable, str(Path(__file__).resolve()), "--inspect"] if os.name == "nt"
               else ["ps", "-eo", "pid=,ppid=,comm=,args="])
    # A helper bounds wall time even if an OS query stalls. Files bound capture
    # memory; reject oversized results before reading them into this process.
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        subprocess.run(command, stdout=output, stderr=errors, timeout=10, check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        if output.tell() > 16 * 1024 * 1024:
            raise RuntimeError("process inspection limit exceeded")
        output.seek(0)
        data = output.read().decode("utf-8", errors="strict")
    if os.name == "nt":
        rows = json.loads(data)
        if not isinstance(rows, list) or not rows:
            raise RuntimeError("incomplete process inspection")
        return rows
    rows = []
    for line in data.splitlines():
        fields = line.split(None, 3)
        if len(fields) != 4:
            raise RuntimeError("incomplete process inspection")
        pid = int(fields[0])
        try:
            cwd = os.readlink(f"/proc/{pid}/cwd")
        except OSError:
            cwd = None
        rows.append({"pid": pid, "parent": int(fields[1]), "name": fields[2], "command": fields[3], "cwd": cwd})
    return rows


#: A wrapper or native-image process exists only while an invocation is in flight, so
#: its presence is proof of an active build. A daemon outlives its build and needs its
#: own state consulted before it counts as one.
_ACTIVE_MARKERS = ("gradlewrappermain", "nativeimagegeneratorrunner")
_GRADLE_DAEMON_MARKER = "gradledaemon"
_KOTLIN_DAEMON_MARKER = "kotlincompiledaemon"


def _daemon_kind(name, command):
    lower = (command or "").lower()
    if any(marker in lower for marker in _ACTIVE_MARKERS) or "native-image" in name.lower():
        return "active"
    if _GRADLE_DAEMON_MARKER in lower:
        return "gradle-daemon"
    if _KOTLIN_DAEMON_MARKER in lower:
        return "kotlin-daemon"
    return None


def build_activity(owned_pids=(), whitelist=(), daemon_states=None, *, rows=None):
    """Classify every external build candidate on this host.

    Each row carries ``kind`` (``active``/``gradle-daemon``/``kotlin-daemon``), the
    daemon's registry ``state`` and ``idle_seconds`` where Gradle publishes them, and
    ``blocking``: whether it must hold up admission.

    A Gradle daemon blocks only while it is doing build work. Its registry state is
    Gradle's own bookkeeping; a daemon whose state cannot be read stays blocking, so an
    unreadable or future registry format can only be over-cautious, never permissive.

    A Kotlin compile daemon has no registry of its own. It compiles solely on behalf of
    a Gradle build, so it blocks exactly when some wrapper or non-idle Gradle daemon is
    present -- never on its own account.

    Commands are sensitive; the caller redacts. An unavailable build candidate command
    line raises RuntimeError so admission defers rather than guesses. Supplying
    ``rows`` reuses a caller's process inspection without scanning the host again.
    """
    try:
        rows = _inspect_processes() if rows is None else rows
        owned = {int(pid) for pid in owned_pids}
        whitelist_set = set()
        whitelist_patterns = []
        for item in (whitelist or ()):
            if isinstance(item, int):
                whitelist_set.add(item)
            elif isinstance(item, str):
                if item.isdigit():
                    whitelist_set.add(int(item))
                else:
                    whitelist_patterns.append(item.lower())
        changed = True
        while changed:
            before = len(owned)
            owned.update(row["pid"] for row in rows if row["parent"] in owned)
            changed = before != len(owned)
        if daemon_states is None:
            daemon_states = gradle_registry.daemon_states(process_rows=rows)
        candidates = []
        for row in rows:
            if row["pid"] in owned or row["pid"] in whitelist_set:
                continue
            if any(fnmatch.fnmatch(row["name"].lower(), pat) for pat in whitelist_patterns):
                continue
            command = row["command"]
            if _candidate(row["name"]) and not command:
                raise RuntimeError("build candidate command line unavailable")
            lower = (command or "").lower()
            if any(fnmatch.fnmatch(lower, pat) for pat in whitelist_patterns):
                continue
            if not _candidate(row["name"]):
                continue
            kind = _daemon_kind(row["name"], command)
            if kind is None:
                continue
            entry = daemon_states.get(row["pid"]) if kind == "gradle-daemon" else None
            candidates.append({
                "pid": row["pid"], "parent": row["parent"], "name": row["name"], "command": command,
                "cwd": row.get("cwd"),
                "kind": kind,
                "state": entry["state"] if entry else ("unknown" if kind == "gradle-daemon" else None),
                "idle_seconds": gradle_registry.idle_seconds(entry) if entry else None,
                "gradle_version": entry.get("version") if entry else None,
            })
        for row in candidates:
            if row["kind"] == "gradle-daemon":
                row["blocking"] = row["state"] not in gradle_registry.INACTIVE_STATES
            else:
                row["blocking"] = row["kind"] == "active"
        # A Kotlin daemon can only be compiling for a build that is itself still present.
        build_in_flight = any(row["blocking"] for row in candidates if row["kind"] != "kotlin-daemon")
        for row in candidates:
            if row["kind"] == "kotlin-daemon":
                row["blocking"] = build_in_flight
        return candidates
    except Exception as exc:
        raise RuntimeError("host process inspection unavailable; build admission denied") from exc


def external_builds(owned_pids=(), whitelist=(), daemon_states=None, *, rows=None):
    """Return the external build rows that must hold up admission."""
    return [row for row in build_activity(owned_pids, whitelist, daemon_states, rows=rows) if row["blocking"]]


def reap_idle_gradle_daemons(owned_pids=(), whitelist=(), min_idle_seconds=120.0, include_compile_daemons=True):
    """Ask idle Gradle daemons to shut down and report what was stopped.

    Only daemons that Gradle itself reports as ``Idle``, that have been idle for at
    least ``min_idle_seconds``, and that belong to no coordinator lane are eligible.
    Eligibility is re-read from the registry immediately before signalling, so a daemon
    that picked up work in between is left alone.

    The signal is a request to terminate, never a forced kill: the daemon runs its own
    shutdown and releases its lock files. A daemon that ignores it stays alive and stays
    a candidate, which is the safe direction. Compile daemons are stopped only as
    children of a daemon reaped in the same pass, so a compile daemon serving some other
    build is never touched.
    """
    reaped = []
    activity = build_activity(owned_pids, whitelist)
    eligible = []
    for row in activity:
        if row["kind"] != "gradle-daemon" or row["state"] not in gradle_registry.INACTIVE_STATES:
            continue
        idle_for = row["idle_seconds"]
        if idle_for is None or idle_for < min_idle_seconds:
            continue
        eligible.append(row)
    if not eligible:
        return reaped
    fresh = gradle_registry.daemon_states(process_rows=activity)
    owned = {int(pid) for pid in owned_pids}
    for row in eligible:
        pid = row["pid"]
        entry = fresh.get(pid)
        if pid in owned or entry is None or entry["state"] not in gradle_registry.INACTIVE_STATES:
            continue
        children = [c["pid"] for c in activity
                    if c["kind"] == "kotlin-daemon" and c["parent"] == pid] if include_compile_daemons else []
        for target in [pid] + children:
            if _request_termination(target):
                reaped.append({
                    "pid": target,
                    "kind": "gradle-daemon" if target == pid else "kotlin-daemon",
                    "state": row["state"],
                    "idle_seconds": row["idle_seconds"],
                    "gradle_version": row["gradle_version"],
                })
    return reaped


def _request_termination(pid) -> bool:
    """Request an orderly shutdown of one process. Never escalates to a forced kill."""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid)], timeout=10, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            os.kill(int(pid), signal.SIGTERM)
        return True
    except (OSError, ValueError, subprocess.SubprocessError):
        return False


if __name__ == "__main__":
    if os.name != "nt" or sys.argv[1:] != ["--inspect"]:
        raise SystemExit("internal process inspection helper")
    print(json.dumps(_inspect_windows(), ensure_ascii=True))

"""Gradle daemon registry reader.

Gradle records every daemon it owns in ``<gradle user home>/daemon/<version>/registry.bin``
together with that daemon's own view of whether it is working. Reading it lets the
coordinator distinguish a daemon that is compiling from one that merely exists, which
process inspection alone cannot do.

The file is written by ``DaemonRegistryContent.Serializer`` through
``OutputStreamBackedEncoder``, i.e. ``DataOutputStream`` framing: fixed-width
big-endian integers, ``writeUTF`` strings with a two-byte length, and
``writeBinary``/``writeSmallInt`` both widening to a 4-byte int. It is not Kryo
varint encoded. Layout verified against the shipped Gradle sources and parsed
end-to-end for 8.10 through 9.5.0.

The format is Gradle's internal contract, not a published one. Every parse failure
is reported as "unknown", never as "idle", so a caller that cannot read a daemon's
state keeps treating it as a blocker.
"""
from __future__ import annotations

from pathlib import Path, PurePosixPath, PureWindowsPath
import os
import re
import shlex
import struct
import time

# org.gradle.launcher.daemon.server.api.DaemonState
STATES = ("Idle", "Busy", "Canceled", "StopRequested", "Stopped", "ForceStopped", "Broken")

# States in which the daemon is not doing build work and holds nothing a build needs.
# Canceled/StopRequested/Broken are deliberately excluded: those daemons are mid-teardown
# or unhealthy and are left alone.
INACTIVE_STATES = ("Idle",)

_MAX_REGISTRY_BYTES = 4 * 1024 * 1024
_MAX_ENTRIES = 512


class _Reader:
    """DataOutputStream-compatible reader over a bytes buffer."""

    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def _take(self, count):
        if count < 0 or self.pos + count > len(self.data):
            raise ValueError("truncated daemon registry")
        chunk = self.data[self.pos:self.pos + count]
        self.pos += count
        return chunk

    def byte(self):
        return self._take(1)[0]

    def boolean(self):
        return self.byte() != 0

    def int32(self):
        return struct.unpack(">i", self._take(4))[0]

    def long64(self):
        return struct.unpack(">q", self._take(8))[0]

    def binary(self):
        return self._take(self.int32())

    def utf(self):
        length = struct.unpack(">H", self._take(2))[0]
        return self._take(length).decode("utf-8", "replace")

    def nullable_utf(self):
        return self.utf() if self.boolean() else None


def _parse(data: bytes) -> list[dict]:
    reader = _Reader(data)
    if not reader.boolean():
        return []
    count = reader.int32()
    if count < 0 or count > _MAX_ENTRIES:
        raise ValueError("implausible daemon count")
    addresses = []
    for _ in range(count):
        kind = reader.byte()
        if kind != 1:
            # Type 0 is a plain socket address and type 2 is Java object serialization.
            # Neither is produced by current daemons; refuse rather than misread the stream.
            raise ValueError(f"unsupported daemon address type {kind}")
        reader.long64(), reader.long64()  # canonical UUID
        port = reader.int32()
        candidates = [reader.binary() for _ in range(reader.int32())]
        addresses.append({"port": port, "candidates": [".".join(str(b) for b in c) for c in candidates]})
    daemons = []
    for _ in range(count):
        index = reader.int32()
        reader.binary()  # authentication token; never logged or stored
        state = reader.byte()
        last_busy_ms = reader.long64()
        reader.nullable_utf()  # uid
        java_home = reader.utf()
        java_version = reader.int32()
        reader.utf()  # vendor
        registry_dir = reader.utf()
        pid = reader.long64() if reader.boolean() else None
        idle_timeout_ms = reader.int32() if reader.boolean() else None
        for _ in range(reader.int32()):
            reader.utf()  # daemon JVM options
        reader.boolean()  # instrumentation agent
        reader.int32()  # native services mode
        if reader.boolean():
            reader.int32()  # priority
        daemons.append({
            "pid": int(pid) if pid is not None else None,
            "state": STATES[state] if 0 <= state < len(STATES) else f"Unknown({state})",
            "last_busy_ms": last_busy_ms,
            "java_home": java_home,
            "java_version": java_version,
            "idle_timeout_ms": idle_timeout_ms,
            "port": addresses[index]["port"] if 0 <= index < len(addresses) else None,
        })
    return daemons


def _command_arguments(command, windows):
    """Split inspected command lines without consuming Windows path separators."""
    if not windows:
        try:
            return shlex.split(command)
        except ValueError:
            return []
    # Windows quoting follows the C runtime rules used by Java and list2cmdline:
    # backslashes are literal except immediately before a double quote.
    arguments, argument = [], []
    quoted = False
    index = 0
    started = False
    while index < len(command):
        char = command[index]
        if char in " \t" and not quoted:
            if started:
                arguments.append("".join(argument))
                argument, started = [], False
            index += 1
            continue
        started = True
        if char == "\\":
            end = index
            while end < len(command) and command[end] == "\\":
                end += 1
            count = end - index
            if end < len(command) and command[end] == '"':
                argument.extend("\\" * (count // 2))
                if count % 2:
                    argument.append('"')
                else:
                    quoted = not quoted
                index = end + 1
            else:
                argument.extend("\\" * count)
                index = end
            continue
        if char == '"':
            quoted = not quoted
        else:
            argument.append(char)
        index += 1
    if quoted:
        return []
    if started:
        arguments.append("".join(argument))
    return arguments


def process_gradle_homes(command, *, windows=None) -> list[str]:
    """Find registry homes from a daemon's explicit option or wrapper classpath.

    These paths locate Gradle's state; they never establish process ownership.
    An explicit user home takes precedence over the distribution's download home.
    Multiple conflicting homes are returned so callers can leave that PID unknown.
    """
    windows = os.name == "nt" if windows is None else windows
    args = _command_arguments(command or "", windows)
    if "org.gradle.launcher.daemon.bootstrap.GradleDaemon" not in args:
        return []
    path_type = PureWindowsPath if windows else PurePosixPath
    explicit, classpaths = [], []
    for index, arg in enumerate(args):
        if arg.startswith(("-Dgradle.user.home=", "--gradle-user-home=")):
            explicit.append(arg.split("=", 1)[1])
        elif arg in ("-g", "--gradle-user-home") and index + 1 < len(args):
            explicit.append(args[index + 1])
        elif arg in ("-cp", "-classpath", "--class-path") and index + 1 < len(args):
            classpaths.extend(args[index + 1].split(";" if windows else ":"))
        elif arg.startswith("--class-path="):
            classpaths.extend(arg.split("=", 1)[1].split(";" if windows else ":"))
    if explicit:
        # Relative options depend on a working directory that inspection may not
        # provide. Do not reinterpret them relative to the coordinator's directory.
        return list(dict.fromkeys(str(path_type(value)) for value in explicit
                                  if path_type(value).is_absolute()))
    homes = []
    for value in classpaths:
        path = path_type(value)
        match = re.fullmatch(r"gradle-(?:daemon-main|launcher)-(.+)\.jar", path.name)
        if not path.is_absolute() or not match or len(path.parts) < 8:
            continue
        version = match.group(1)
        # Only Gradle's normal wrapper distribution layout identifies its user home.
        if (path.parts[-7:-5] == ("wrapper", "dists") and
                path.parts[-5] in (f"gradle-{version}-bin", f"gradle-{version}-all") and
                path.parts[-3:-1] == (f"gradle-{version}", "lib")):
            homes.append(str(path.parents[6]))
    return list(dict.fromkeys(homes))


def registry_files(gradle_user_home=None) -> list[Path]:
    """Every daemon registry within one Gradle user home."""
    if gradle_user_home is None:
        gradle_user_home = os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle")
    base = Path(gradle_user_home) / "daemon"
    try:
        if not base.is_dir():
            return []
        return sorted(p for p in base.glob("*/registry.bin") if p.is_file())
    except OSError:
        return []


def daemon_states(gradle_user_home=None, *, process_rows=()) -> dict[int, dict]:
    """Map PID to daemon state for every registered Gradle daemon.

    Discover custom homes from the supplied process command lines as well as the
    default home. A known process home is authoritative for that PID: an unreadable
    registry cannot fall back to a stale PID entry in another home's registry.
    """
    default_home = gradle_user_home or os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle")
    homes = {Path(default_home)}
    process_homes = {}
    for row in process_rows:
        discovered = {Path(home) for home in process_gradle_homes(row.get("command"))}
        if discovered:
            process_homes[int(row["pid"])] = discovered
            homes.update(discovered)
    states = {}
    for path in sorted({path for home in homes for path in registry_files(home)}):
        try:
            if path.stat().st_size > _MAX_REGISTRY_BYTES:
                continue
            entries = _parse(path.read_bytes())
        except (OSError, ValueError, struct.error, IndexError):
            continue
        for entry in entries:
            if entry["pid"] is None:
                continue
            expected = process_homes.get(entry["pid"])
            if expected is not None and expected != {path.parents[2]}:
                continue
            entry["registry"] = str(path)
            entry["version"] = path.parent.name
            states[entry["pid"]] = entry
    return states


def idle_seconds(entry, now=None) -> float | None:
    """Seconds since this daemon last did build work, or None when unknown.

    A clock skew that puts ``lastBusy`` in the future reports 0 rather than a
    negative age, so a caller's minimum-idle gate cannot be skipped by it.
    """
    last_busy = entry.get("last_busy_ms")
    if not last_busy or last_busy <= 0:
        return None
    return max(0.0, (now if now is not None else time.time()) - last_busy / 1000.0)

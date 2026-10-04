# Forward arguments without evaluating shell text. Requires Python 3.11 or newer.
$ErrorActionPreference = 'Stop'
$coordinatorCli = Join-Path $PSScriptRoot 'tooling/build-coordinator/cli.py'
& python $coordinatorCli @args
exit $LASTEXITCODE

# NexaChat API smoke test (PowerShell parity wrapper).
# The assertions live in smoke_api.py; this only forwards arguments.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
python "$here\smoke_api.py" @args
exit $LASTEXITCODE

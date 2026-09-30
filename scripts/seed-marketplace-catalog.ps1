param(
    [ValidateSet('seed', 'reseed', 'verify', 'inventory', 'reset')]
    [string]$Command = 'seed',
    [string]$SourceJsonl
)

$arguments = @('scripts/marketplace_catalog_seed.py', $Command)
if ($SourceJsonl) {
    $arguments += @('--source-jsonl', $SourceJsonl)
}
python @arguments
exit $LASTEXITCODE

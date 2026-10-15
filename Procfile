api: go -C server tool -modfile=tools/go.mod air
sync: cd app && $(go -C ../server tool -modfile=tools/go.mod -n air) -c sync/.air.toml
web: pnpm --dir app/platforms/web dev

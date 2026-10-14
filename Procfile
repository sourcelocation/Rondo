# Air is a tool of server/go.mod; the sync server's runs from app/.
api: go -C server tool air
sync: cd app && $(go -C ../server tool -n air) -c sync/.air.toml
web: pnpm --dir app/platforms/web dev

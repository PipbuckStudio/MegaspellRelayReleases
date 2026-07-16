## Megaspell Relay Releases ##

Caching relay/CDN for Megaspell GitHub release artifacts. Used by the launcher as a fallback when
direct GitHub access times out or hits GitHub's API rate limit.

### Key features ###

- No database. Downloaded artifacts are cached to disk on first request and served from disk
  forever after. There is no automatic eviction — old versions must be deleted manually from the
  mounted storage volume.
- Uses its own server-side GitHub token (5000 req/hour authenticated) instead of the 60 req/hour
  unauthenticated limit, so it absorbs rate-limit exhaustion on behalf of every client behind it.
- Serves two independent artifact namespaces, `game` and `launcher`, each backed by its own
  configurable upstream GitHub repository.
- Multi-volume GitHub release assets (`<platform>.zip.001`, `.002`, ...) are fetched and
  concatenated server-side into a single file, so clients never need to deal with multi-volume
  archives themselves.

### API ###

| Method | Path                                          | Params                         | Purpose                                   |
|--------|-----------------------------------------------|---------------------------------|--------------------------------------------|
| GET    | `/releases/{namespace}`                       | `limit` (default 20), `platform` | List last releases, newest first          |
| GET    | `/releases/{namespace}/latest`                | `platform`                      | Resolve latest version metadata           |
| GET    | `/releases/{namespace}/{version}/download`    | `platform` (required)            | Cache-or-fetch-then-stream a single artifact |

`{namespace}` is `game` or `launcher`. `{version}` also accepts the literal `latest`.

Responses:
- `404` — the version/platform genuinely doesn't exist (not cached, not on GitHub). Clients should
  not retry another mirror for this.
- `503` (with `Retry-After`) — upstream GitHub is rate-limited.
- `502` — upstream GitHub is unreachable or timed out.

See `requests.http` for example calls.

### Deployment in container ###

Required environment properties:

- `GAME_REPOSITORY` - example `PipbuckStudio/Megaspell-Releases`
- `LAUNCHER_REPOSITORY` - example `PipbuckStudio/MegaspellLauncher`
- `GAME_GITHUB_TOKEN` - optional, required only if the game repo is private
- `LAUNCHER_GITHUB_TOKEN` - optional, required only if the launcher repo is private
- `STORAGE_DIR` - optional, defaults to `/data`

Example compose configuration:

```yaml
megaspell-relay-releases:
  image: ghcr.io/pipbuckstudio/megaspell-relay-releases:latest
  restart: unless-stopped
  ports:
    - "8080:8080"
  environment:
    GAME_REPOSITORY: PipbuckStudio/Megaspell-Releases
    LAUNCHER_REPOSITORY: PipbuckStudio/MegaspellLauncher
    GAME_GITHUB_TOKEN: "${GAME_GITHUB_TOKEN}"
  volumes:
    - ./data:/data
```

**Important:** the storage volume must be mounted to a host path (or a named volume you can
access), since deleting cached versions is a manual operation — there is no admin API or TTL for
this. To force a version to be re-fetched from GitHub, delete its file from the volume.

### Known limitations ###

- The `launcher` namespace's download endpoint expects assets named `<platform>.zip[.NNN]`, same
  as the `game` namespace. Electron-builder's actual installer filenames don't follow this
  convention today, so the launcher currently only uses this service's `launcher` list/latest
  endpoints (for resilient update checks), not its download endpoint.
- Custom, user-added release sources in the launcher are not proxied by this service — only the
  studio's default game/launcher repos are.

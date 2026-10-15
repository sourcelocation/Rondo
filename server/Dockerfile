# The Go server (`rondo serve`, `rondo migrate`), built from the repository root: mail reads brand/dist.
# The Go CI tests with (.github/workflows/ci.yml): a change to one is a change to both. go.mod's `go`
# is only the oldest it builds with.
FROM golang:1.27.2 AS build
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /rondo ./cmd/rondo

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /rondo /rondo
COPY brand/dist/tokens.json /brand/
COPY brand/dist/email/mark.png /brand/email/
ENV RONDO_BRAND_DIR=/brand RONDO_HTTP_ADDR=:8080
ENTRYPOINT ["/rondo"]
CMD ["serve"]

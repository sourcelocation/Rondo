# Development only (compose.dev.yaml): Air rebuilds, migrates and restarts the Go server from the
# bind-mounted repository on every save (.air.toml). It listens where its release image does.
FROM golang:1.26.9-alpine
RUN go install github.com/air-verse/air@v1.67.4
ENV CGO_ENABLED=0 RONDO_HTTP_ADDR=:8080
WORKDIR /src/server
ENTRYPOINT ["air"]

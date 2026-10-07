# The Go server (`rondo serve`, `rondo migrate`), built from the repository root: mail reads brand/dist.
FROM golang:1.26 AS build
WORKDIR /src/server
COPY server/go.mod server/go.sum ./
RUN go mod download
COPY server/ ./
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /rondo ./cmd/rondo

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /rondo /rondo
COPY brand/dist/tokens.json /brand/
COPY brand/dist/email/mark.png /brand/email/
ENV RONDO_BRAND_DIR=/brand RONDO_HTTP_ADDR=:8080
ENTRYPOINT ["/rondo"]
CMD ["serve"]

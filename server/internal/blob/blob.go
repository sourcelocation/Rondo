// Package blob stores files by key: media (by hash) and exports. Two stores: a folder (development,
// self-hosting) and S3, which covers Cloudflare R2.
package blob

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
)

// Store keeps files. Keys are paths such as "media/<hash>" or "exports/<token>.zip".
type Store interface {
	// UploadURL is where a client sends a file's bytes with PUT, and the headers to send. [sum] is
	// the file's SHA-256, which a store that can refuses a different file with.
	UploadURL(ctx context.Context, key, mime string, size int64, sum []byte) (string, map[string]string, error)
	Put(ctx context.Context, key, mime string, r io.Reader) error
	// Check is nil when key holds the file of [size] bytes whose SHA-256 is [sum].
	Check(ctx context.Context, key string, sum []byte, size int64) error
	// URL is where key can be read for ttl.
	URL(ctx context.Context, key string, ttl time.Duration) (string, error)
	Open(ctx context.Context, key string) (io.ReadCloser, error)
	Delete(ctx context.Context, key string) error
}

// Folder keeps files under Dir; Go serves them at Base + "/blobs/" + key and receives uploads at
// Base + "/media/<hash>".
type Folder struct {
	Dir  string
	Base string
}

func (f Folder) path(key string) (string, error) {
	clean := filepath.Clean("/" + key)
	if strings.Contains(key, "..") {
		return "", errors.New("blob: bad key")
	}
	return filepath.Join(f.Dir, clean), nil
}

func (f Folder) UploadURL(_ context.Context, key, _ string, _ int64, _ []byte) (string, map[string]string, error) {
	return f.Base + "/" + key, map[string]string{"Content-Type": "application/octet-stream"}, nil
}

func (f Folder) Put(_ context.Context, key, _ string, r io.Reader) error {
	path, err := f.path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".upload-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := io.Copy(tmp, r); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// Check reads the file back: it's on this machine's disk.
func (f Folder) Check(_ context.Context, key string, sum []byte, size int64) error {
	path, err := f.path(key)
	if err != nil {
		return err
	}
	r, err := os.Open(path) //nolint:gosec // f.path keeps keys inside the folder
	if err != nil {
		return err
	}
	defer r.Close()
	return matches(r, sum, size)
}

func (f Folder) URL(_ context.Context, key string, _ time.Duration) (string, error) {
	return f.Base + "/blobs/" + key, nil
}

func (f Folder) Open(_ context.Context, key string) (io.ReadCloser, error) {
	path, err := f.path(key)
	if err != nil {
		return nil, err
	}
	return os.Open(path) //nolint:gosec // f.path keeps keys inside the folder
}

func (f Folder) Delete(_ context.Context, key string) error {
	path, err := f.path(key)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// S3 keeps files in a bucket. Clients upload with presigned PUTs that carry the SHA-256, so the
// bucket refuses any other file and keeps the checksum, and checking a file needn't download it.
type S3 struct {
	client  *s3.Client
	presign *s3.PresignClient
	bucket  string
}

func NewS3(endpoint, region, bucket, key, secret string) *S3 {
	client := s3.New(s3.Options{
		Region:       region,
		BaseEndpoint: aws.String(endpoint),
		Credentials:  credentials.NewStaticCredentialsProvider(key, secret, ""),
		UsePathStyle: true,
	})
	return &S3{client: client, presign: s3.NewPresignClient(client), bucket: bucket}
}

func (s *S3) UploadURL(ctx context.Context, key, mime string, size int64, sum []byte) (string, map[string]string, error) {
	checksum := base64.StdEncoding.EncodeToString(sum)
	req, err := s.presign.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket: &s.bucket, Key: &key, ContentType: &mime, ContentLength: &size, ChecksumSHA256: &checksum,
	}, s3.WithPresignExpires(15*time.Minute))
	if err != nil {
		return "", nil, err
	}
	// The signature covers these headers: the client sends them as they are.
	headers := map[string]string{}
	for name, values := range req.SignedHeader {
		if !strings.EqualFold(name, "Host") && !strings.EqualFold(name, "Content-Length") && len(values) > 0 {
			headers[name] = values[0]
		}
	}
	return req.URL, headers, nil
}

func (s *S3) Put(ctx context.Context, key, mime string, r io.Reader) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: &s.bucket, Key: &key, ContentType: &mime, Body: r})
	return err
}

// Check compares the checksum the bucket kept. One that keeps none (a store without checksums,
// a file uploaded before them) is read back instead.
func (s *S3) Check(ctx context.Context, key string, sum []byte, size int64) error {
	head, err := s.client.HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: &s.bucket, Key: &key, ChecksumMode: types.ChecksumModeEnabled,
	})
	if err != nil {
		return err
	}
	if got := aws.ToInt64(head.ContentLength); got != size {
		return fmt.Errorf("blob: size %d, expected %d", got, size)
	}
	if head.ChecksumSHA256 != nil {
		if *head.ChecksumSHA256 != base64.StdEncoding.EncodeToString(sum) {
			return errContents
		}
		return nil
	}
	r, err := s.Open(ctx, key)
	if err != nil {
		return err
	}
	defer r.Close()
	return matches(r, sum, size)
}

func (s *S3) URL(ctx context.Context, key string, ttl time.Duration) (string, error) {
	req, err := s.presign.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key}, s3.WithPresignExpires(ttl))
	if err != nil {
		return "", err
	}
	return req.URL, nil
}

func (s *S3) Open(ctx context.Context, key string) (io.ReadCloser, error) {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key})
	if err != nil {
		return nil, err
	}
	return out.Body, nil
}

func (s *S3) Delete(ctx context.Context, key string) error {
	_, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: &s.bucket, Key: &key})
	return err
}

var errContents = errors.New("blob: contents don't match the hash")

// matches reads r through: nil when it's [size] bytes whose SHA-256 is [sum].
func matches(r io.Reader, sum []byte, size int64) error {
	h := sha256.New()
	n, err := io.Copy(h, r)
	if err != nil {
		return err
	}
	if n != size {
		return fmt.Errorf("blob: size %d, expected %d", n, size)
	}
	if !bytes.Equal(h.Sum(nil), sum) {
		return errContents
	}
	return nil
}

// ServeFolder serves a Folder's files (only used with the folder store).
func ServeFolder(dir string) http.Handler {
	return http.StripPrefix("/blobs/", http.FileServer(http.Dir(dir)))
}

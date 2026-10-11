package blob

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"os"
	"strings"
	"testing"
)

func TestFolderCheck(t *testing.T) {
	ctx := context.Background()
	f := Folder{Dir: t.TempDir()}
	file := []byte("a picture")
	sum := sha256.Sum256(file)
	if err := f.Put(ctx, "media/x", "image/png", bytes.NewReader(file)); err != nil {
		t.Fatal(err)
	}
	if err := f.Check(ctx, "media/x", sum[:], int64(len(file))); err != nil {
		t.Fatalf("the stored file: %v", err)
	}
	other := sha256.Sum256([]byte("another picture"))
	if f.Check(ctx, "media/x", other[:], int64(len(file))) == nil {
		t.Fatal("another file's hash matched")
	}
	if f.Check(ctx, "media/x", sum[:], int64(len(file))+1) == nil {
		t.Fatal("another size matched")
	}
}

// The upload's signature covers the checksum, so a client can't leave it out or change it.
func TestUploadURLSignsTheChecksum(t *testing.T) {
	s := NewS3("https://example.r2.cloudflarestorage.com", "auto", "media", "key", "secret")
	sum := sha256.Sum256([]byte("a picture"))
	url, headers, err := s.UploadURL(context.Background(), "media/x", "image/png", 9, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	want := base64.StdEncoding.EncodeToString(sum[:])
	got := ""
	for name, value := range headers {
		if strings.EqualFold(name, "x-amz-checksum-sha256") {
			got = value
		}
	}
	if got != want {
		t.Fatalf("checksum header %q, want %q (headers %v)", got, want, headers)
	}
	if !strings.Contains(strings.ToLower(url), "x-amz-checksum-sha256") {
		t.Fatalf("the signed headers don't name the checksum: %s", url)
	}
}

// Against a real bucket, with RONDO_TEST_S3_ENDPOINT, _BUCKET, _ACCESS_KEY and _SECRET_KEY (the
// compose's `--profile s3` storage, or R2): the bucket refuses a file that isn't the one signed
// for, and Check accepts the one that is.
func TestS3RefusesAnotherFile(t *testing.T) {
	endpoint := os.Getenv("RONDO_TEST_S3_ENDPOINT")
	if endpoint == "" {
		t.Skip("RONDO_TEST_S3_ENDPOINT not set")
	}
	ctx := context.Background()
	s := NewS3(endpoint, "auto", os.Getenv("RONDO_TEST_S3_BUCKET"),
		os.Getenv("RONDO_TEST_S3_ACCESS_KEY"), os.Getenv("RONDO_TEST_S3_SECRET_KEY"))
	file := []byte("a picture, checked by the bucket")
	sum := sha256.Sum256(file)
	key := "media/test-" + base64.RawURLEncoding.EncodeToString(sum[:8])
	t.Cleanup(func() { _ = s.Delete(ctx, key) })
	put := func(body []byte) int {
		url, headers, err := s.UploadURL(ctx, key, "image/png", int64(len(body)), sum[:])
		if err != nil {
			t.Fatal(err)
		}
		req, _ := http.NewRequestWithContext(ctx, http.MethodPut, url, bytes.NewReader(body))
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res.StatusCode
	}
	forged := bytes.ToUpper(file)
	if status := put(forged); status < 400 {
		t.Fatalf("another file of the same size was stored (%d)", status)
	}
	if status := put(file); status >= 300 {
		t.Fatalf("the file itself was refused (%d)", status)
	}
	if err := s.Check(ctx, key, sum[:], int64(len(file))); err != nil {
		t.Fatalf("check: %v", err)
	}
	other := sha256.Sum256(forged)
	if s.Check(ctx, key, other[:], int64(len(file))) == nil {
		t.Fatal("another hash matched")
	}
}

package handlers

import (
	"context"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/blob"
	"github.com/sourcelocation/rondo/server/internal/pro"
	"github.com/sourcelocation/rondo/server/internal/store"
)

const maxMedia = 10 << 20

var mediaTypes = map[string]bool{
	"image/webp": true, "image/png": true, "image/jpeg": true, "image/gif": true,
	"audio/mpeg": true, "audio/mp4": true, "audio/ogg": true, "audio/wav": true, "audio/webm": true,
}

// RequestUpload says where to send a file, after checking its type, size and the quota.
func (s *Server) RequestUpload(ctx context.Context, r api.RequestUploadRequestObject) (api.RequestUploadResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	b := r.Body
	hash, err := hex.DecodeString(b.Hash)
	if err != nil || len(hash) != 32 || !mediaTypes[b.Mime] {
		return nil, fail(http.StatusBadRequest, api.Invalid, "That file can't be stored.")
	}
	if b.Size > maxMedia {
		return nil, fail(http.StatusRequestEntityTooLarge, api.TooLarge, "Files can be up to 10 MB.")
	}
	if m, err := s.Q.Media(ctx, hash); err == nil && m.Verified {
		return api.RequestUpload200JSONResponse{Exists: true}, nil
	}
	u, err := s.user(ctx, id.ID)
	if err != nil {
		return nil, err
	}
	used, err := s.Q.MediaUsage(ctx, &id.ID)
	if err != nil {
		return nil, err
	}
	limit := s.S.FreeMediaBytes
	if pro.Active(u.ProUntil) {
		limit = s.S.ProMediaBytes
	}
	if used+int64(b.Size) > limit {
		return nil, fail(http.StatusForbidden, api.QuotaExceeded, "There's no room for more media on your plan.")
	}
	if err := s.Q.AddMedia(ctx, store.AddMediaParams{Hash: hash, Size: int32(b.Size), Mime: b.Mime, UploadedBy: &id.ID}); err != nil {
		return nil, err
	}
	target, headers, err := s.Blobs.UploadURL(ctx, "media/"+b.Hash, b.Mime, int64(b.Size))
	if err != nil {
		return nil, err
	}
	return api.RequestUpload200JSONResponse{Exists: false, Url: &target, Headers: &headers}, nil
}

func (s *Server) CompleteUpload(ctx context.Context, r api.CompleteUploadRequestObject) (api.CompleteUploadResponseObject, error) {
	if _, err := me(ctx); err != nil {
		return nil, err
	}
	m, err := s.media(ctx, r.Hash)
	if err != nil {
		return nil, err
	}
	if err := blob.Verify(ctx, s.Blobs, r.Hash, int64(m.Size)); err != nil {
		return nil, fail(http.StatusBadRequest, api.Invalid, "The upload didn't arrive complete.")
	}
	return api.CompleteUpload204Response{}, s.Q.VerifyMedia(ctx, m.Hash)
}

func (s *Server) media(ctx context.Context, hash string) (store.Medium, error) {
	raw, err := hex.DecodeString(hash)
	if err != nil {
		return store.Medium{}, errNotFound
	}
	m, err := s.Q.Media(ctx, raw)
	if errors.Is(err, pgx.ErrNoRows) {
		return m, errNotFound
	}
	return m, err
}

// GetMedia redirects to the file: its contents never change, so it may be cached forever.
func (s *Server) GetMedia(ctx context.Context, r api.GetMediaRequestObject) (api.GetMediaResponseObject, error) {
	m, err := s.media(ctx, r.Hash)
	if err != nil {
		return nil, err
	}
	if !m.Verified {
		return nil, errNotFound
	}
	location, err := s.Blobs.URL(ctx, "media/"+r.Hash, 24*time.Hour)
	if err != nil {
		return nil, err
	}
	return api.GetMedia302Response{Headers: api.GetMedia302ResponseHeaders{Location: &location}}, nil
}

// PutMedia receives a file for the folder store; the hash proves it is the file it claims to be.
func (s *Server) PutMedia(ctx context.Context, r api.PutMediaRequestObject) (api.PutMediaResponseObject, error) {
	m, err := s.media(ctx, r.Hash)
	if err != nil {
		return nil, err
	}
	if err := s.Blobs.Put(ctx, "media/"+r.Hash, m.Mime, io.LimitReader(r.Body, maxMedia+1)); err != nil {
		return nil, err
	}
	if err := blob.Verify(ctx, s.Blobs, r.Hash, int64(m.Size)); err != nil {
		_ = s.Blobs.Delete(ctx, "media/"+r.Hash)
		return nil, fail(http.StatusBadRequest, api.Invalid, "That file doesn't match its hash.")
	}
	return api.PutMedia204Response{}, s.Q.VerifyMedia(ctx, m.Hash)
}

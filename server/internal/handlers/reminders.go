package handlers

import (
	"context"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/api"
	"github.com/sourcelocation/rondo/server/internal/notify"
	"github.com/sourcelocation/rondo/server/internal/problem"
	"github.com/sourcelocation/rondo/server/internal/store"
)

// What devices report for reminders and profiles, and where pushes go.

func (s *Server) PutSummary(ctx context.Context, r api.PutSummaryRequestObject) (api.PutSummaryResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	due := make([]int32, 0, len(r.Body.Due))
	for _, n := range r.Body.Due {
		if n < 0 {
			return nil, problem.Invalid("Counts aren't negative.")
		}
		due = append(due, int32(n))
	}
	if len(due) == 0 || len(due) > 7 || r.Body.Learned < 0 {
		return nil, problem.Invalid("A summary has one to seven days.")
	}
	if _, err := s.user(ctx, id.ID); err != nil {
		return nil, err
	}
	return api.PutSummary204Response{}, s.Q.PutSummary(ctx, store.PutSummaryParams{UserID: id.ID, Due: due, Learned: int32(r.Body.Learned)})
}

var platforms = map[api.PushDevicePlatform]int16{api.Web: notify.WebPush, api.Apns: notify.APNs, api.Fcm: notify.FCM}

func (s *Server) PutPushDevice(ctx context.Context, r api.PutPushDeviceRequestObject) (api.PutPushDeviceResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	b := r.Body
	platform, ok := platforms[b.Platform]
	if !ok || b.Endpoint == "" {
		return nil, problem.Invalid("That isn't a device to push to.")
	}
	if platform == notify.WebPush && (b.P256dh == nil || b.Auth == nil) {
		return nil, problem.Invalid("A browser's subscription has its keys.")
	}
	if _, err := s.user(ctx, id.ID); err != nil {
		return nil, err
	}
	d, err := s.Q.PutPushDevice(ctx, store.PutPushDeviceParams{
		ID: uuid.Must(uuid.NewV7()), UserID: id.ID, Platform: platform, Endpoint: b.Endpoint, P256dh: b.P256dh, Auth: b.Auth,
		Reminders: b.Reminders, LocalUntil: b.LocalUntil,
	})
	if err != nil {
		return nil, err
	}
	return api.PutPushDevice200JSONResponse{Id: d.ID}, nil
}

func (s *Server) DeletePushDevice(ctx context.Context, r api.DeletePushDeviceRequestObject) (api.DeletePushDeviceResponseObject, error) {
	id, err := me(ctx)
	if err != nil {
		return nil, err
	}
	return api.DeletePushDevice204Response{}, s.Q.DeletePushDevice(ctx, store.DeletePushDeviceParams{ID: r.DeviceId, UserID: id.ID})
}

func (s *Server) PushKey(context.Context, api.PushKeyRequestObject) (api.PushKeyResponseObject, error) {
	if !s.Pusher.Ready() {
		return api.PushKey200JSONResponse{Key: ""}, nil
	}
	return api.PushKey200JSONResponse{Key: s.Pusher.PublicKey}, nil
}

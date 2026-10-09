package notify

import (
	"time"
)

// The notifications Rondo sends, each in its own words. Links point at the docs or into the app.

const rules = "/docs/rules"

func date(t time.Time) string { return t.Format("2 January 2006") }

func ProStarted(until *time.Time) Message {
	body := "Editors on your shared decks, agents and more room for media are yours."
	if until != nil && until.Before(time.Now().AddDate(50, 0, 0)) {
		body = "Until " + date(*until) + ": editors on your shared decks, agents and more room for media."
	}
	return Message{Kind: "pro_started", Presentation: Modal, Title: "Welcome to Pro", Body: body, LinkLabel: "What's in Pro", LinkPath: "/docs/plans"}
}

var roleNames = map[string]string{"admin": "an admin", "moderator": "a moderator", "support": "on support", "curator": "a curator"}

func RoleGranted(role string) Message {
	return Message{
		Kind: "role_granted", Presentation: Modal, Title: "You're " + roleNames[role] + " now",
		Body:      "Staff tools work once you sign in with a passkey. They're in the sidebar, and on public pages.",
		LinkLabel: "Community rules", LinkPath: rules,
	}
}

func RoleRemoved(role string) Message {
	return Message{Kind: "role_removed", Presentation: Toast, Title: "You're no longer " + roleNames[role], Body: ""}
}

// Frozen tells the owner that a staff member went over the hourly limits and lost their powers.
func Frozen(who, kind string) Message {
	return Message{
		Kind: "staff_frozen", Presentation: Modal, Title: "Staff powers frozen",
		Body:      who + " went over the hourly limit for " + kind + ". Their staff powers are off until you undo the freeze in the log.",
		LinkLabel: "Open the log", LinkPath: "/app/staff/log", Email: true,
	}
}

// Reason is a staff reason in the words people read.
func Reason(key string) string {
	if r, ok := map[string]string{
		"spam": "Spam", "harassment": "Harassment", "hate": "Hate", "sexual": "Sexual content", "impersonation": "Impersonation",
		"copyright": "Copyright", "illegal": "Illegal content", "name": "An inappropriate name", "evasion": "Getting around a restriction",
	}[key]; ok {
		return r
	}
	return "Breaking the community rules"
}

func because(reason string, note *string) string {
	out := "Reason: " + Reason(reason) + "."
	if note != nil && *note != "" {
		out += " " + *note
	}
	return out
}

func until(t *time.Time, what string) string {
	if t == nil || t.After(time.Now().AddDate(100, 0, 0)) {
		return what + " for good."
	}
	return what + " until " + date(*t) + "."
}

func Warned(reason string, note *string) Message {
	return Message{
		Kind: "warning", Presentation: Modal, Title: "A warning from Rondo's moderators",
		Body:      because(reason, note) + " Another one may restrict your account.",
		LinkLabel: "Community rules", LinkPath: rules,
	}
}

func Withdrawn() Message {
	return Message{Kind: "warning_withdrawn", Presentation: Toast, Title: "A warning was withdrawn"}
}

func Restricted(end *time.Time, reason string, note *string) Message {
	return Message{
		Kind: "restricted", Presentation: Modal, Title: "Your account is restricted",
		Body:      until(end, "You can't publish, share, invite or change your name") + " Studying and syncing work as always. " + because(reason, note),
		LinkLabel: "Community rules", LinkPath: rules,
	}
}

func Banned(end *time.Time, reason string, note *string) Message {
	return Message{
		Kind: "banned", Presentation: Elsewhere, Email: true, Title: "Your Rondo account is suspended",
		Body: until(end, "You can't sign in") + " " + because(reason, note) +
			" A Stripe subscription won't renew; one from the App Store or Google Play has to be cancelled there.",
		LinkLabel: "Community rules", LinkPath: rules,
	}
}

func Lifted() Message {
	return Message{Kind: "lifted", Presentation: Toast, Email: true, Title: "Your account's restrictions were lifted",
		Body: "Everything works again."}
}

func Renamed() Message {
	return Message{
		Kind: "renamed", Presentation: Toast, Title: "A moderator reset your name",
		Body: "Pick a new one whenever you like.", LinkLabel: "Edit profile", LinkPath: "/app/settings/account",
	}
}

func NameBack() Message {
	return Message{Kind: "name_back", Presentation: Toast, Title: "Your name is back"}
}

func DeckRemoved(deck, reason string, note *string) Message {
	return Message{
		Kind: "deck_removed", Presentation: Modal, Title: "“" + deck + "” was taken off Discover",
		Body: because(reason, note) + " It stays yours, and you can still share it privately.", LinkLabel: "Community rules", LinkPath: rules,
	}
}

func DeckBack(deck string) Message {
	return Message{Kind: "deck_back", Presentation: Toast, Title: "“" + deck + "” is back on Discover"}
}

func TakenDown(deck string) Message {
	return Message{
		Kind: "followed_taken_down", Presentation: Toast, Title: "“" + deck + "” was removed",
		Body: "A deck you followed broke the community rules, so it's no longer shared.", LinkLabel: "Community rules", LinkPath: rules,
	}
}

func Reviewed() Message {
	return Message{Kind: "report_reviewed", Presentation: Toast, Title: "We looked at your report", Body: "Thanks for keeping Rondo good."}
}

func RemindersPaused() Message {
	return Message{
		Kind: "reminders_paused", Presentation: Toast, Title: "Reminders are paused",
		Body: "You haven't studied after the last few, so they stop until you do.", LinkLabel: "Reminders", LinkPath: "/app/settings/reminders",
	}
}

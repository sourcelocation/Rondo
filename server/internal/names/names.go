// Package names decides what usernames, display names and flags people may have.
package names

import (
	"crypto/rand"
	"math/big"
	"regexp"
	"strings"
	"unicode"

	goaway "github.com/TwiN/go-away"
	"golang.org/x/text/language"
)

var (
	shape     = regexp.MustCompile(`^[a-z0-9_]{3,20}$`)
	automatic = regexp.MustCompile(`^user[0-9]+$`)
	// Taken by Rondo's addresses and roles, or easily mistaken for staff.
	reserved = map[string]bool{
		"about": true, "abuse": true, "account": true, "admin": true, "administrator": true, "anonymous": true, "api": true,
		"app": true, "billing": true, "contact": true, "discover": true, "docs": true, "email": true, "everyone": true,
		"help": true, "here": true, "info": true, "legal": true, "login": true, "mail": true, "matthewsource": true,
		"me": true, "mod": true, "moderator": true, "moderators": true, "null": true, "official": true, "owner": true,
		"privacy": true, "pro": true, "profile": true, "register": true, "root": true, "security": true, "settings": true,
		"signin": true, "signup": true, "staff": true, "support": true, "system": true, "team": true, "terms": true,
		"undefined": true, "www": true, "you": true,
	}
	impersonating = []string{"rondo", "admin", "moderator", "official"}
)

// Why a username can't be had.
const (
	Invalid  = "invalid"  // not 3 to 20 of a-z, 0-9 and _
	Reserved = "reserved" // Rondo's own, or looks like staff
	Rude     = "rude"
	Taken    = "taken"
	Held     = "held" // someone else's until a month after they changed it
)

// Username normalizes [s] (lowercase, trimmed) and says why it can't be anyone's username, or "".
func Username(s string) (string, string) {
	s = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(s), "@")))
	switch {
	case !shape.MatchString(s):
		return s, Invalid
	case reserved[s] || automatic.MatchString(s):
		return s, Reserved
	case goaway.IsProfane(strings.ReplaceAll(s, "_", " ")):
		return s, Rude
	}
	for _, word := range impersonating {
		if strings.Contains(s, word) {
			return s, Reserved
		}
	}
	return s, ""
}

// Automatic says whether [s] is a username Rondo gave, which may be changed without waiting.
func Automatic(s string) bool { return automatic.MatchString(s) }

// Random is a username Rondo gives: user and eight digits.
func Random() string {
	n, err := rand.Int(rand.Reader, big.NewInt(90_000_000))
	if err != nil {
		panic(err)
	}
	return "user" + n.Add(n, big.NewInt(10_000_000)).String()
}

// DisplayName trims [s] and says whether it may be someone's display name: 1 to 60 characters,
// nothing invisible, nothing rude.
func DisplayName(s string) (string, bool) {
	s = strings.Join(strings.Fields(s), " ")
	n := len([]rune(s))
	if n == 0 || n > 60 || goaway.IsProfane(s) {
		return s, false
	}
	for _, r := range s {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return s, false
		}
	}
	return s, true
}

// Flag normalizes a country code (ISO 3166-1 alpha-2) and says whether it's one.
func Flag(code string) (string, bool) {
	code = strings.ToUpper(strings.TrimSpace(code))
	if len(code) != 2 {
		return code, false
	}
	region, err := language.ParseRegion(code)
	return code, err == nil && region.IsCountry() && region.String() == code
}

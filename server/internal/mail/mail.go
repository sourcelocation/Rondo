// Package mail sends Rondo's email (sign-in codes from Kratos, invitations, exports) through SMTP,
// in one layout coloured by the brand: shasp's tokens.json, and its mark carried inline.
package mail

import (
	"bytes"
	"context"
	"encoding/json"
	"html/template"
	"os"
	"path/filepath"
	"strings"

	gomail "github.com/wneessen/go-mail"
)

// Message is one email: a heading, a paragraph, and a code or a button.
type Message struct {
	To, Subject, Heading, Text string
	Code                       string
	Link, LinkLabel            string
}

type Config struct {
	Host, User, Password, TLS, From, BrandDir string
	Port                                      int
}

type Mailer struct {
	cfg    Config
	colors map[string]string
	mark   []byte
}

// New reads the brand from cfg.BrandDir (brand/dist); without it, mail uses the same colours
// and no mark.
func New(cfg Config) *Mailer {
	m := &Mailer{cfg: cfg, colors: map[string]string{
		"bg.canvas": "#F2EFE7", "bg.raised": "#FFFFFF", "text.primary": "#141413", "text.tertiary": "#6B6A63", "bg.accent": "#2F3BFF", "text.on-accent": "#FFFFFF",
	}}
	var tokens struct {
		Colors []struct{ Name, Light string } `json:"colors"`
	}
	if raw, err := os.ReadFile(filepath.Join(cfg.BrandDir, "tokens.json")); err == nil && json.Unmarshal(raw, &tokens) == nil {
		for _, c := range tokens.Colors {
			m.colors[c.Name] = c.Light
		}
	}
	m.mark, _ = os.ReadFile(filepath.Join(cfg.BrandDir, "email", "mark.png"))
	return m
}

var layout = template.Must(template.New("mail").Parse(`<!doctype html><html><body style="margin:0;background:{{index .C "bg.canvas"}};font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:{{index .C "text.primary"}}">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center" style="padding:40px 16px">
<table role="presentation" width="100%" style="max-width:480px;background:{{index .C "bg.raised"}};border-radius:16px" cellpadding="0" cellspacing="0"><tr><td style="padding:32px">
{{if .Mark}}<img src="cid:mark.png" alt="Rondo" width="40" height="40" style="display:block;margin-bottom:24px">{{end}}
<h1 style="font-size:22px;line-height:28px;margin:0 0 12px">{{.M.Heading}}</h1>
<p style="font-size:15px;line-height:22px;margin:0 0 24px;color:{{index .C "text.tertiary"}}">{{.M.Text}}</p>
{{if .M.Code}}<p style="font-size:32px;letter-spacing:6px;font-weight:600;margin:0">{{.M.Code}}</p>{{end}}
{{if .M.Link}}<a href="{{.M.Link}}" style="display:inline-block;background:{{index .C "bg.accent"}};color:{{index .C "text.on-accent"}};text-decoration:none;padding:12px 20px;border-radius:10px;font-weight:600">{{.M.LinkLabel}}</a>{{end}}
</td></tr></table></td></tr></table></body></html>`))

func (m *Mailer) Send(ctx context.Context, msg Message) error {
	var html bytes.Buffer
	if err := layout.Execute(&html, map[string]any{"C": m.colors, "Mark": len(m.mark) > 0, "M": msg}); err != nil {
		return err
	}
	text := msg.Heading + "\n\n" + msg.Text + "\n\n" + msg.Code + msg.Link + "\n"
	e := gomail.NewMsg()
	if err := e.From(m.cfg.From); err != nil {
		return err
	}
	if err := e.To(msg.To); err != nil {
		return err
	}
	e.Subject(msg.Subject)
	e.SetBodyString(gomail.TypeTextPlain, text)
	e.AddAlternativeString(gomail.TypeTextHTML, html.String())
	if len(m.mark) > 0 {
		if err := e.EmbedReader("mark.png", bytes.NewReader(m.mark)); err != nil {
			return err
		}
	}
	opts := []gomail.Option{gomail.WithPort(m.cfg.Port)}
	switch m.cfg.TLS {
	case "none":
		opts = append(opts, gomail.WithTLSPolicy(gomail.NoTLS))
	case "tls":
		opts = append(opts, gomail.WithSSL())
	default:
		opts = append(opts, gomail.WithTLSPolicy(gomail.TLSMandatory))
	}
	if m.cfg.User != "" {
		opts = append(opts, gomail.WithSMTPAuth(gomail.SMTPAuthPlain), gomail.WithUsername(m.cfg.User), gomail.WithPassword(m.cfg.Password))
	}
	client, err := gomail.NewClient(m.cfg.Host, opts...)
	if err != nil {
		return err
	}
	return client.DialAndSendWithContext(ctx, e)
}

// FromKratos turns a message Kratos hands over into Rondo's wording.
func FromKratos(kind, to, code, link string) Message {
	switch {
	case strings.HasPrefix(kind, "verification"):
		return Message{To: to, Subject: "Confirm your email", Heading: "Confirm your email", Text: "Enter this code in Rondo to confirm this address.", Code: code}
	case strings.HasSuffix(kind, "_invalid"):
		return Message{To: to, Subject: "Signing in to Rondo", Heading: "No Rondo account here yet", Text: "Someone tried to sign in with this address, but it has no Rondo account. If it was you, sign up with it in the app."}
	case strings.HasPrefix(kind, "recovery"):
		return Message{To: to, Subject: "Your Rondo code", Heading: "Your code", Text: "Enter this code in Rondo.", Code: code, Link: link, LinkLabel: "Open Rondo"}
	default:
		return Message{To: to, Subject: "Your Rondo sign-in code", Heading: "Your sign-in code", Text: "Enter this code in Rondo. It works for 15 minutes.", Code: code}
	}
}

func Invite(to, from, deck, link string) Message {
	return Message{To: to, Subject: from + " shared “" + deck + "” with you", Heading: deck, Text: from + " invited you to study “" + deck + "” on Rondo.", Link: link, LinkLabel: "Open the deck"}
}

func Export(to, link string) Message {
	return Message{To: to, Subject: "Your Rondo export is ready", Heading: "Your export is ready", Text: "Everything you have in Rondo, as one ZIP file. The link works for 7 days.", Link: link, LinkLabel: "Download"}
}

func NewEmailCode(to, code string) Message {
	return Message{To: to, Subject: "Confirm your new email for Rondo", Heading: "Confirm your new email", Text: "Enter this code in Rondo to sign in with this address from now on. It works for 15 minutes.", Code: code}
}

func EmailChanged(to, email string) Message {
	return Message{To: to, Subject: "Your Rondo email changed", Heading: "Your email changed", Text: "Your Rondo account now signs in with " + email + ". If you didn't do this, write to support@matthewsource.com from this address."}
}

func ResetCode(to, code string) Message {
	return Message{To: to, Subject: "Removing your second step", Heading: "Remove your second step?", Text: "Someone asked to remove the authenticator app from your Rondo account. If it was you, enter this code; it's removed a week later. If it wasn't, ignore this email.", Code: code}
}

func ResetScheduled(to, date string) Message {
	return Message{To: to, Subject: "Your second step will be removed", Heading: "Your second step goes on " + date, Text: "Until then, signing in with your authenticator app or a recovery code cancels it. If you didn't ask for this, sign in now."}
}

func ResetDone(to string) Message {
	return Message{To: to, Subject: "Your second step was removed", Heading: "Your second step was removed", Text: "Your Rondo account signs in without the authenticator app now. Turn it on again in Profile › Security."}
}

func AgentConnected(to, agent string) Message {
	return Message{To: to, Subject: agent + " can use your Rondo", Heading: agent + " was connected", Text: agent + " can now read and change your decks and notes. You can disconnect it in Profile › Agents. If this wasn't you, disconnect it and sign out everywhere."}
}

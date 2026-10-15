package mail

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"net"
	"strings"
	"testing"

	"github.com/riverqueue/river"
)

func TestAnEmailIsNamedOnTheSendersDomainAndSaysWhereItsFrom(t *testing.T) {
	m := New(Config{From: "Rondo <rondo@matthewsource.com>", PublicURL: "https://rondo.matthewsource.com"})
	e, err := m.compose(Invite("bob@example.com", "Alice", "<b>HSK</b> 1", "https://rondo.matthewsource.com/app/i/abc"))
	if err != nil {
		t.Fatal(err)
	}
	var headers bytes.Buffer
	if _, err := e.WriteTo(&headers); err != nil {
		t.Fatal(err)
	}
	bodies := ""
	for _, p := range e.GetParts() {
		content, _ := p.GetContent()
		bodies += string(content)
	}
	for in, wants := range map[string][]string{
		headers.String(): {"Message-ID: <", "@matthewsource.com>\r\n", "Auto-Submitted: auto-generated"},
		bodies: {
			"Open the deck: https://rondo.matthewsource.com/app/i/abc", // the plain text, its link labelled
			"Rondo · https://rondo.matthewsource.com",
			`>rondo.matthewsource.com</a>`, // the HTML's footer
			"&lt;b&gt;HSK&lt;/b&gt; 1",
		},
	} {
		for _, want := range wants {
			if !strings.Contains(in, want) {
				t.Errorf("no %q in\n%s", want, in)
			}
		}
	}
}

func TestKratosMessagesForNoAccountHaveNoCode(t *testing.T) {
	if m := FromKratos("verification_code_invalid", "a@example.com", "", ""); m.Heading != "No Rondo account here yet" || m.Code != "" {
		t.Errorf("verification for no account: %+v", m)
	}
	if m := FromKratos("login_code_valid", "a@example.com", "123456", ""); m.Code != "123456" || m.Lifespan != codeLife {
		t.Errorf("sign-in code: %+v", m)
	}
}

func TestARefusalForGoodCancelsTheJob(t *testing.T) {
	smtp, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = smtp.Close() }()
	go func() {
		c, err := smtp.Accept()
		if err != nil {
			return
		}
		defer func() { _ = c.Close() }()
		in := bufio.NewReader(c)
		_, _ = c.Write([]byte("220 test\r\n"))
		for {
			line, err := in.ReadString('\n')
			if err != nil {
				return
			}
			command, _, _ := strings.Cut(strings.TrimSpace(line), " ")
			switch strings.ToUpper(command) {
			case "EHLO", "HELO":
				_, _ = c.Write([]byte("250 test\r\n"))
			case "RCPT":
				_, _ = c.Write([]byte("550 5.1.1 no such mailbox\r\n"))
			case "QUIT":
				_, _ = c.Write([]byte("221 bye\r\n"))
				return
			default:
				_, _ = c.Write([]byte("250 ok\r\n"))
			}
		}
	}()
	m := New(Config{Host: "127.0.0.1", Port: smtp.Addr().(*net.TCPAddr).Port, TLS: "none", From: "rondo@example.com"})
	err = m.Send(context.Background(), Export("nobody@example.com", "https://example.com/x.zip"))
	var cancel *river.JobCancelError
	if !errors.As(err, &cancel) {
		t.Fatalf("got %v, want the job cancelled", err)
	}
}

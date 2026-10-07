import { KeyRound } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { Empty, Loading, Mark, Spinner } from "@/components/kit";
import { InputOTP, InputOTPGroup, InputOTPSlot } from "@/components/ui/input-otp";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { app, s, useApp, useScreen, type SignInScreen, type SignInState } from "@/rondo";

export const GOOGLE = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;
export const APPLE = import.meta.env.VITE_APPLE_CLIENT_ID as string | undefined;

const script = (src: string) =>
  new Promise<void>((loaded, failed) =>
    document.head.append(Object.assign(document.createElement("script"), { src, onload: loaded, onerror: failed })),
  );
const sha256 = async (text: string) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)))]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");

declare const google: {
  accounts: { id: { initialize(o: object): void; renderButton(e: HTMLElement, o: object): void } };
};
declare const AppleID: { auth: { init(o: object): void; signIn(): Promise<{ authorization: { id_token: string } }> } };

/** Google's own button; its ID token (with the nonce it carries) goes to [onToken]. */
export function GoogleButton({
  onToken,
  text = "continue_with",
}: {
  onToken: (token: string, nonce: string) => void;
  text?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const handler = useRef(onToken);
  handler.current = onToken;
  useEffect(() => {
    const nonce = crypto.randomUUID();
    script("https://accounts.google.com/gsi/client").then(() => {
      google.accounts.id.initialize({
        client_id: GOOGLE,
        nonce,
        callback: (r: { credential: string }) => handler.current(r.credential, nonce),
      });
      if (box.current) google.accounts.id.renderButton(box.current, { size: "large", width: 320, text });
    });
  }, [text]);
  return <div ref={box} className="flex justify-center" />;
}

/** Signing in with Apple's own popup: its ID token, and the nonce it carries (hashed). */
export async function appleToken(): Promise<[string, string]> {
  const nonce = crypto.randomUUID();
  await script("https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js");
  AppleID.auth.init({
    clientId: APPLE,
    scope: "email",
    redirectURI: location.origin,
    usePopup: true,
    nonce: await sha256(nonce),
  });
  return [(await AppleID.auth.signIn()).authorization.id_token, nonce];
}

const apple = async (signIn: SignInScreen) => {
  const [token, nonce] = await appleToken();
  signIn.idToken("apple", token, nonce);
};

/** The second step: a code from the authenticator app, or a recovery code. */
function SecondStep({ st, signIn }: { st: SignInState; signIn: SignInScreen }) {
  const [code, setCode] = useState("");
  const [recovery, setRecovery] = useState(false);
  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-3xl">{s.secondStepTitle}</h1>
        <p className="text-subtle">{recovery ? s.recoveryCode : s.secondStepHint}</p>
      </div>
      <form
        className="grid justify-items-center gap-3"
        onSubmit={(e) => (e.preventDefault(), signIn.secondStep(code, recovery))}
      >
        {recovery ? (
          <Input
            autoFocus
            aria-label={s.recoveryCode}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className="font-mono"
          />
        ) : (
          <InputOTP
            maxLength={6}
            value={code}
            onChange={setCode}
            onComplete={(c: string) => signIn.secondStep(c, false)}
            autoFocus
            aria-label={s.code}
          >
            <InputOTPGroup>
              {[0, 1, 2, 3, 4, 5].map((i) => (
                <InputOTPSlot key={i} index={i} />
              ))}
            </InputOTPGroup>
          </InputOTP>
        )}
        {st.error && (
          <p role="alert" className="text-sm text-destructive">
            {st.error}
          </p>
        )}
        <Button type="submit" className="w-full" disabled={st.busy || !code.trim()}>
          {st.busy && <Spinner />} {s.signIn}
        </Button>
      </form>
      <div className="grid gap-1">
        <Button variant="ghost" onClick={() => (setRecovery(!recovery), setCode(""))}>
          {recovery ? s.useAuthenticator : s.useRecoveryCode}
        </Button>
        <Button variant="ghost" onClick={() => signIn.lostSecondStep()}>
          {s.lostAuthenticator}
        </Button>
      </div>
    </>
  );
}

/** Lost the second step: asking (by an emailed code) to remove it a week later. */
function LostStep({ st, signIn }: { st: SignInState; signIn: SignInScreen }) {
  const [email, setEmail] = useState(st.email);
  const [code, setCode] = useState("");
  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-3xl">{st.step === "lost_sent" ? s.lostSentTitle : s.lostTitle}</h1>
        <p className="text-subtle">{st.step === "lost_sent" ? s.lostSentHint : s.lostHint}</p>
      </div>
      {st.step === "lost" && (
        <form className="grid gap-3" onSubmit={(e) => (e.preventDefault(), signIn.requestReset(email))}>
          <Input
            type="email"
            autoFocus
            required
            aria-label={s.email}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Button type="submit" disabled={st.busy || !email.trim()}>
            {st.busy && <Spinner />} {s.sendCode}
          </Button>
        </form>
      )}
      {st.step === "lost_code" && (
        <form
          className="grid justify-items-center gap-3"
          onSubmit={(e) => (e.preventDefault(), signIn.confirmReset(code))}
        >
          <InputOTP maxLength={6} value={code} onChange={setCode} autoFocus aria-label={s.code}>
            <InputOTPGroup>
              {[0, 1, 2, 3, 4, 5].map((i) => (
                <InputOTPSlot key={i} index={i} />
              ))}
            </InputOTPGroup>
          </InputOTP>
          <Button type="submit" className="w-full" disabled={st.busy || code.length < 6}>
            {st.busy && <Spinner />} {s.done}
          </Button>
        </form>
      )}
      {st.error && (
        <p role="alert" className="text-sm text-destructive">
          {st.error}
        </p>
      )}
      <Button variant="ghost" onClick={() => signIn.back()}>
        {s.back}
      </Button>
    </>
  );
}

const Frame = ({ children }: { children: React.ReactNode }) => (
  <main className="grid min-h-dvh place-items-center bg-background px-6 py-12">
    <div className="grid w-full max-w-sm gap-6 text-center">
      <Mark className="mx-auto h-12 w-auto" />
      {children}
    </div>
  </main>
);

/**
 * Signing in; when an agent's OAuth request sent someone here (Hydra's `login_challenge`), signing
 * that agent in for them once they are.
 */
export function SignInPage() {
  const [params] = useSearchParams();
  const challenge = params.get("login_challenge");
  const st = useApp();
  useEffect(() => {
    if (challenge && st?.signedIn) app.acceptLogin(challenge, (url) => location.assign(url));
  }, [challenge, st?.signedIn]);
  return challenge && st?.signedIn ? <Loading /> : <SignIn />;
}

/** Where to go after signing in: an address of this app only, never another site. */
export function afterSignIn(next: string | null): string {
  if (!next?.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return "/";
  const url = new URL(next, location.origin);
  return url.origin === location.origin ? url.pathname + url.search + url.hash : "/";
}

/** Seconds until [at], ticking. */
function useCountdown(at: number) {
  const [now, setNow] = useState(() => app.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(app.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return Math.max(0, Math.ceil((at - now) / 1000));
}

function CodeStep({ st, signIn }: { st: SignInState; signIn: SignInScreen }) {
  const [code, setCode] = useState("");
  const wait = useCountdown(st.resendAt);
  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-3xl">{s.checkEmail}</h1>
        <p className="text-subtle">
          {s.codeSent} {st.email}
        </p>
      </div>
      <form className="grid justify-items-center gap-3" onSubmit={(e) => (e.preventDefault(), signIn.confirm(code))}>
        <InputOTP
          maxLength={6}
          value={code}
          onChange={setCode}
          onComplete={(c: string) => signIn.confirm(c)}
          autoFocus
          aria-label={s.code}
          aria-invalid={!!st.error}
        >
          <InputOTPGroup>
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <InputOTPSlot key={i} index={i} />
            ))}
          </InputOTPGroup>
        </InputOTP>
        {st.error && (
          <p role="alert" className="text-sm text-destructive">
            {st.error}
          </p>
        )}
        <Button type="submit" className="w-full" disabled={st.busy || code.length < 6}>
          {st.busy && <Spinner />} {s.signIn}
        </Button>
      </form>
      <div className="grid gap-1">
        <Button variant="ghost" disabled={wait > 0 || st.busy} onClick={() => signIn.resend()}>
          {wait > 0 ? s.resendIn(wait) : s.resend}
        </Button>
        <Button variant="ghost" onClick={() => signIn.back()}>
          {s.otherEmail}
        </Button>
      </div>
    </>
  );
}

/** Signing in or up with an email code, a passkey, Google or Apple; or going on without an account. */
function SignIn() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [st, signIn] = useScreen(() => app.signIn());
  const [email, setEmail] = useState("");
  const next = afterSignIn(params.get("next"));
  useEffect(() => void (st?.step === "done" && navigate(next, { replace: true })), [st?.step, navigate, next]);
  if (!st) return <Loading />;
  return (
    <Frame>
      {st.step === "second" ? (
        <SecondStep st={st} signIn={signIn} />
      ) : st.step.startsWith("lost") ? (
        <LostStep st={st} signIn={signIn} />
      ) : st.step === "code" ? (
        <CodeStep st={st} signIn={signIn} />
      ) : (
        <>
          <h1 className="text-3xl">{s.signInTitle}</h1>
          <p className="text-subtle">{s.signInHint}</p>
          <form className="grid gap-3 text-left" onSubmit={(e) => (e.preventDefault(), signIn.sendCode(email))}>
            <Input
              type="email"
              autoComplete="email webauthn"
              autoFocus
              required
              aria-label={s.email}
              aria-invalid={!!st.error}
              placeholder={s.email}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            {st.error && (
              <p role="alert" className="text-sm text-destructive">
                {st.error}
              </p>
            )}
            <Button type="submit" disabled={st.busy || !email.trim()}>
              {st.busy && <Spinner />} {s.continueEmail}
            </Button>
          </form>
          <div className="grid gap-2">
            {GOOGLE && <GoogleButton onToken={(token, nonce) => signIn.idToken("google", token, nonce)} />}
            {APPLE && (
              <Button variant="outline" onClick={() => apple(signIn)} disabled={st.busy}>
                {s.apple}
              </Button>
            )}
            <Button variant="ghost" onClick={() => signIn.passkey()} disabled={st.busy}>
              <KeyRound /> {s.usePasskey}
            </Button>
          </div>
          <div className="grid gap-1">
            <Button variant="link" asChild>
              <Link to={next}>{s.continueWithout}</Link>
            </Button>
            <p className="text-xs text-muted-foreground">{s.withoutAccount}</p>
          </div>
          <p className="text-xs text-muted-foreground">
            {s.agreeTo}{" "}
            <a className="text-link hover:underline" href={`${location.origin}/terms`}>
              {s.terms}
            </a>{" "}
            {s.and}{" "}
            <a className="text-link hover:underline" href={`${location.origin}/privacy`}>
              {s.privacy}
            </a>
            .
          </p>
        </>
      )}
    </Frame>
  );
}

/** An agent asking for access, through Hydra. */
export function Consent() {
  const [params] = useSearchParams();
  const challenge = params.get("consent_challenge") ?? "";
  const [st, consent] = useScreen(() => app.consent(challenge), [challenge]);
  if (!st) return <Loading />;
  if (!st.signedIn)
    return (
      <Frame>
        <p>{s.error("unauthorized")}</p>
        <Button asChild>
          <Link to={`/sign-in?next=${encodeURIComponent(location.pathname + location.search)}`}>{s.signIn}</Link>
        </Button>
      </Frame>
    );
  if (!st.client) return <Empty>{s.error("not_found")}</Empty>;
  return (
    <Frame>
      <h1>{s.consentTitle}</h1>
      <p className="text-subtle">{s.wantsAccess(st.client)}</p>
      {st.uri && (
        <a href={st.uri} className="text-sm text-link">
          {st.uri}
        </a>
      )}
      <div className="flex flex-wrap justify-center gap-2">
        {st.scopes.map((sc) => (
          <code key={sc} className="rounded bg-muted px-2 py-1 text-xs">
            {sc}
          </code>
        ))}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Button variant="secondary" onClick={() => consent.decide(false, (u) => location.assign(u))}>
          {s.deny}
        </Button>
        <Button onClick={() => consent.decide(true, (u) => location.assign(u))}>{s.allow}</Button>
      </div>
    </Frame>
  );
}

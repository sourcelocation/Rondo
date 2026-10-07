import { Copy, Laptop, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { confirm, Header, Loading, Section } from "@/components/kit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { app, date, s, useScreen, type SecurityScreen, type SecurityState } from "@/rondo";
import { APPLE, appleToken, GOOGLE, GoogleButton } from "@/screens/Account";

/** A code to type: six digits, or longer for recovery and email codes. */
function CodeForm({ label, busy, onCode }: { label: string; busy: boolean; onCode: (code: string) => void }) {
  const [code, setCode] = useState("");
  return (
    <form className="flex max-w-sm gap-2" onSubmit={(e) => (e.preventDefault(), code.trim() && onCode(code))}>
      <Input
        aria-label={label}
        placeholder={label}
        inputMode="numeric"
        autoComplete="one-time-code"
        value={code}
        onChange={(e) => setCode(e.target.value)}
      />
      <Button type="submit" disabled={busy || !code.trim()}>
        {s.done}
      </Button>
    </form>
  );
}

function Authenticator({ st, security }: { st: SecurityState; security: SecurityScreen }) {
  if (st.recoveryCodes)
    return (
      <div className="grid gap-3">
        <p className="text-sm text-subtle">{s.recoveryHint}</p>
        <ul className="grid grid-cols-2 gap-1 rounded-lg border p-4 font-mono text-sm sm:grid-cols-3">
          {st.recoveryCodes.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            onClick={() => (navigator.clipboard.writeText(st.recoveryCodes!.join("\n")), toast(s.copied))}
          >
            <Copy /> {s.copy}
          </Button>
          <Button onClick={() => security.keepRecoveryCodes()}>{s.savedThem}</Button>
        </div>
      </div>
    );
  if (st.setupQr)
    return (
      <div className="grid gap-3">
        <p className="text-sm text-subtle">{s.scanHint}</p>
        <img src={st.setupQr} alt="" className="size-48 rounded-lg border bg-white p-2" />
        {st.setupKey && <code className="text-sm break-all">{st.setupKey}</code>}
        <CodeForm label={s.code} busy={st.busy} onCode={(c) => security.confirmTotp(c)} />
        <Button variant="ghost" className="justify-self-start" onClick={() => security.cancelTotp()}>
          {s.cancel}
        </Button>
      </div>
    );
  return (
    <div className="flex flex-wrap items-center gap-3">
      <span className="min-w-0 flex-1 text-sm">
        {st.twoFactor ? s.authenticatorOn : s.authenticatorOff}
        {!st.twoFactor && <span className="block text-subtle">{s.passkeyEnough}</span>}
      </span>
      {st.twoFactor ? (
        <>
          <Button variant="outline" onClick={() => security.newRecoveryCodes()}>
            {s.newCodes}
          </Button>
          <Button
            variant="ghost"
            onClick={async () =>
              (await confirm(`${s.turnOff}?`, s.authenticatorOff, s.turnOff)) && security.disableTotp()
            }
          >
            {s.turnOff}
          </Button>
        </>
      ) : (
        <Button onClick={() => security.startTotp()}>{s.turnOn}</Button>
      )}
    </div>
  );
}

/** The account's security: the authenticator app, devices, email and linked accounts. */
export function Security() {
  const [st, security] = useScreen(() => app.security());
  const [email, setEmail] = useState("");
  if (!st) return <Loading />;
  return (
    <>
      <Header up="/profile" title={s.security} />
      {st.resetAt && (
        <div className="mb-8 flex flex-wrap items-center gap-3 rounded-lg bg-warning px-4 py-3 text-sm">
          <span className="flex-1">{s.resetPending(date(st.resetAt))}</span>
          <Button size="sm" variant="ghost" onClick={() => security.cancelReset()}>
            {s.cancelReset}
          </Button>
        </div>
      )}
      {st.confirming && (
        <div className="mb-8 grid gap-3 rounded-lg border p-4">
          <p className="text-sm">{s.confirmIdentity}</p>
          <CodeForm label={s.code} busy={st.busy} onCode={(c) => security.confirm(c)} />
        </div>
      )}
      <Section title={s.authenticator}>
        <Authenticator st={st} security={security} />
      </Section>
      <Section
        title={s.devices}
        action={
          st.devices.length > 1 && (
            <Button size="sm" variant="ghost" onClick={() => security.signOutOthers()}>
              {s.signOutOthers}
            </Button>
          )
        }
      >
        <ul className="grid gap-1">
          {st.devices.map((d) => (
            <li key={d.id} className="flex flex-wrap items-center gap-3 rounded-lg px-3 py-2">
              <Laptop className="size-4 text-muted-foreground" />
              <span className="min-w-0 flex-1 text-sm">
                {d.device}
                <span className="text-subtle">
                  {d.place && ` · ${d.place}`}
                  {d.since && ` · ${date(d.since)}`}
                </span>
              </span>
              {d.current ? (
                <Badge variant="secondary">{s.thisDevice}</Badge>
              ) : (
                <Button size="sm" variant="ghost" onClick={() => security.signOutDevice(d.id)}>
                  {s.signOut}
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Section>
      <Section title={s.emailLabel}>
        <p className="mb-3 text-sm">{st.email}</p>
        {st.emailSent ? (
          <div className="grid gap-2">
            <p className="text-sm text-subtle">
              {s.confirmEmail} {st.emailSent}
            </p>
            <CodeForm label={s.code} busy={st.busy} onCode={(c) => security.confirmEmail(c)} />
          </div>
        ) : (
          <form
            className="flex max-w-md gap-2"
            onSubmit={(e) => (e.preventDefault(), email.trim() && security.changeEmail(email))}
          >
            <Input
              type="email"
              aria-label={s.newEmail}
              placeholder={s.newEmail}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <Button type="submit" variant="outline" disabled={st.busy || !email.trim()}>
              {s.changeEmail}
            </Button>
          </form>
        )}
      </Section>
      {(GOOGLE || APPLE || st.links.length > 0) && (
        <Section title={s.linked}>
          <div className="grid gap-3">
            {st.links.map((p) => (
              <div key={p} className="flex items-center gap-3">
                <ShieldCheck className="size-4 text-muted-foreground" />
                <span className="flex-1 text-sm capitalize">{p}</span>
                <Button size="sm" variant="ghost" onClick={() => security.unlink(p)}>
                  {s.unlink}
                </Button>
              </div>
            ))}
            {GOOGLE && !st.links.includes("google") && (
              <div className="justify-self-start">
                <GoogleButton text="signin_with" onToken={(token, nonce) => security.link("google", token, nonce)} />
              </div>
            )}
            {APPLE && !st.links.includes("apple") && (
              <Button
                variant="outline"
                className="justify-self-start"
                onClick={async () => {
                  const [token, nonce] = await appleToken();
                  security.link("apple", token, nonce);
                }}
              >
                {s.linkApple}
              </Button>
            )}
          </div>
        </Section>
      )}
    </>
  );
}

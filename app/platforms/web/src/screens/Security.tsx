import { Copy, KeyRound, Laptop, Smartphone, Tablet } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { confirm, LearnMore, Loading, Row, Rows, Section, Spinner } from "@/components/kit";
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
        autoFocus
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

/** The authenticator app: turning it on (scan, then a code), the recovery codes it makes, turning it off. */
function Authenticator({ st, security }: { st: SecurityState; security: SecurityScreen }) {
  const action =
    st.recoveryCodes || st.setupQr ? null : st.twoFactor ? (
      <Button
        variant="outline"
        disabled={st.busy}
        onClick={async () => (await confirm(`${s.turnOff}?`, s.authenticatorOff, s.turnOff)) && security.disableTotp()}
      >
        {s.turnOff}
      </Button>
    ) : (
      <Button disabled={st.busy} onClick={() => security.startTotp()}>
        {s.turnOn}
      </Button>
    );
  return (
    <Row
      title={s.authenticator}
      hint={
        st.twoFactor ? (
          s.authenticatorOn
        ) : (
          <>
            {s.authenticatorOff} {s.passkeyEnough}
          </>
        )
      }
      action={action}
    >
      {st.recoveryCodes ? (
        <div className="grid gap-3">
          <p className="text-sm font-medium">{s.recoveryCodes}</p>
          <p className="text-sm text-subtle">{s.recoveryHint}</p>
          <ul className="grid grid-cols-2 gap-1 rounded-lg border bg-background p-4 font-mono text-sm sm:grid-cols-3">
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
            <Button disabled={st.busy} onClick={() => security.keepRecoveryCodes()}>
              {s.savedThem}
            </Button>
          </div>
        </div>
      ) : (
        st.setupQr && (
          <div className="grid gap-3">
            <p className="text-sm text-subtle">{s.scanHint}</p>
            <img src={st.setupQr} alt="" className="size-48 rounded-lg border bg-white p-2" />
            {st.setupKey && <code className="text-sm break-all">{st.setupKey}</code>}
            <CodeForm label={s.code} busy={st.busy} onCode={(c) => security.confirmTotp(c)} />
            <Button variant="ghost" className="justify-self-start" onClick={() => security.cancelTotp()}>
              {s.cancel}
            </Button>
          </div>
        )
      )}
    </Row>
  );
}

/** A Google or Apple account to sign in with: linked, or a way to link it. */
function Linked({ provider, st, security }: { provider: string; st: SecurityState; security: SecurityScreen }) {
  const linked = st.links.includes(provider);
  const link =
    provider === "google" ? (
      <GoogleButton text="signin_with" onToken={(token, nonce) => security.link("google", token, nonce)} />
    ) : (
      <Button
        variant="outline"
        disabled={st.busy}
        onClick={async () => {
          const [token, nonce] = await appleToken();
          security.link("apple", token, nonce);
        }}
      >
        {s.linkApple}
      </Button>
    );
  return (
    <Row
      title={<span className="capitalize">{provider}</span>}
      hint={linked ? s.linkedAccountHint : s.linkAccountHint}
      action={
        linked ? (
          <Button variant="outline" disabled={st.busy} onClick={() => security.unlink(provider)}>
            {s.unlink}
          </Button>
        ) : (
          link
        )
      }
    />
  );
}

/** What a device is, from how it's described: a phone, a tablet, or a computer. */
const deviceIcon = (device: string) =>
  /iPhone|Android/.test(device) ? Smartphone : /iPad/.test(device) ? Tablet : Laptop;

/** The account's security: ways to sign in, the second step, and where it's signed in. */
export function Security() {
  const [st, security] = useScreen(() => app.security());
  const [email, setEmail] = useState<string | null>(null);
  if (!st) return <Loading />;
  const providers = [...new Set([...(GOOGLE ? ["google"] : []), ...(APPLE ? ["apple"] : []), ...st.links])];
  return (
    <>
      {st.resetAt && (
        <div className="mb-8 flex flex-wrap items-center gap-3 rounded-lg bg-warning px-4 py-3 text-sm">
          <span className="flex-1">{s.resetPending(date(st.resetAt))}</span>
          <Button size="sm" variant="ghost" onClick={() => security.cancelReset()}>
            {s.cancelReset}
          </Button>
        </div>
      )}
      {st.confirming && (
        <div className="mb-8 grid gap-3 rounded-lg bg-warning p-4">
          <p className="text-sm">{s.confirmIdentity}</p>
          <CodeForm label={s.code} busy={st.busy} onCode={(c) => security.confirm(c)} />
        </div>
      )}
      <Section title={s.signInMethods} action={<LearnMore page="security" />}>
        <Rows>
          <Row
            title={s.emailLabel}
            hint={st.email}
            action={
              email === null &&
              !st.emailSent && (
                <Button variant="outline" onClick={() => setEmail("")}>
                  {s.change}
                </Button>
              )
            }
          >
            {st.emailSent ? (
              <div className="grid gap-2">
                <p className="text-sm text-subtle">
                  {s.confirmEmail} {st.emailSent}
                </p>
                <CodeForm label={s.code} busy={st.busy} onCode={(c) => (security.confirmEmail(c), setEmail(null))} />
              </div>
            ) : (
              email !== null && (
                <form
                  className="flex max-w-md flex-wrap gap-2"
                  onSubmit={(e) => (e.preventDefault(), email.trim() && security.changeEmail(email))}
                >
                  <Input
                    autoFocus
                    type="email"
                    aria-label={s.newEmail}
                    placeholder={s.newEmail}
                    className="min-w-48 flex-1"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                  <Button type="submit" disabled={st.busy || !email.trim()}>
                    {st.busy && <Spinner />} {s.sendCode}
                  </Button>
                  <Button type="button" variant="ghost" onClick={() => setEmail(null)}>
                    {s.cancel}
                  </Button>
                </form>
              )
            )}
          </Row>
          <Row
            title={s.passkeys}
            hint={s.passkeyHint}
            action={
              <Button variant="outline" disabled={st.busy} onClick={() => security.addPasskey()}>
                <KeyRound /> {s.addPasskey}
              </Button>
            }
          />
          {providers.map((p) => (
            <Linked key={p} provider={p} st={st} security={security} />
          ))}
        </Rows>
      </Section>
      <Section title={s.secondStep}>
        <Rows>
          <Authenticator st={st} security={security} />
          {st.twoFactor && !st.recoveryCodes && (
            <Row
              title={s.recoveryCodes}
              hint={s.recoveryCodesHint}
              action={
                <Button variant="outline" disabled={st.busy} onClick={() => security.newRecoveryCodes()}>
                  {s.newCodes}
                </Button>
              }
            />
          )}
        </Rows>
      </Section>
      <Section
        title={s.devices}
        action={
          st.devices.length > 1 && (
            <Button
              size="sm"
              variant="ghost"
              onClick={async () =>
                (await confirm(`${s.signOutOthers}?`, undefined, s.signOutOthers, false)) && security.signOutOthers()
              }
            >
              {s.signOutOthers}
            </Button>
          )
        }
      >
        <Rows>
          {st.devices.map((d) => (
            <Row
              key={d.id}
              icon={deviceIcon(d.device)}
              title={d.device}
              hint={[d.place, d.since && date(d.since)].filter(Boolean).join(" · ") || undefined}
              action={
                d.current ? (
                  <Badge variant="secondary">{s.thisDevice}</Badge>
                ) : (
                  <Button variant="outline" disabled={st.busy} onClick={() => security.signOutDevice(d.id)}>
                    {s.signOut}
                  </Button>
                )
              }
            />
          ))}
        </Rows>
      </Section>
    </>
  );
}

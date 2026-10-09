import { useEffect, useState } from 'react';
import { UserPlus } from 'lucide-react';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { api, writeErrorMessage } from '../services/api';
import { useOrg } from '../auth/OrgContext';
import { clearInviteToken } from '../auth/deepLinks';

type State =
  | { kind: 'checking' }
  | { kind: 'confirm'; name: string }
  | { kind: 'invalid' }
  | { kind: 'joining'; name: string }
  | { kind: 'error'; name: string; message: string };

/**
 * Asks the signed-in user to confirm an admin's invite link before using it.
 *
 * The link alone does nothing: opening it, or being signed in when it is
 * opened, must not drop someone into an organization they did not choose.
 * Confirming joins at once — there is no approval to wait for, because the
 * admin issuing a single-use link already made that decision.
 */
export function InviteAcceptDialog({ token, onDone }: { token: string; onDone: () => void }) {
  const { t } = useAppTranslation();
  const { refresh, select } = useOrg();
  const [state, setState] = useState<State>({ kind: 'checking' });

  useEffect(() => {
    let cancelled = false;
    api.previewInviteLink(token)
      .then(preview => {
        if (cancelled) return;
        setState(preview.valid && preview.organization
          ? { kind: 'confirm', name: preview.organization.name }
          : { kind: 'invalid' });
      })
      .catch(() => { if (!cancelled) setState({ kind: 'invalid' }); });
    return () => { cancelled = true; };
  }, [token]);

  function close() {
    clearInviteToken();
    onDone();
  }

  async function join(name: string) {
    setState({ kind: 'joining', name });
    try {
      const org = await api.acceptInviteLink(token);
      clearInviteToken();
      await refresh();
      select(org.id);
      onDone();
    } catch (err) {
      setState({ kind: 'error', name, message: writeErrorMessage(err, t('inviteAccept.invalid')) });
    }
  }

  const name = 'name' in state ? state.name : '';

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="invite-accept-title"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.85)',
        backdropFilter: 'blur(10px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1100,
        padding: '20px',
      }}
    >
      <div className="glass-panel-glow" style={{ width: '100%', maxWidth: '440px', borderRadius: '20px', padding: '28px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
        <h2 id="invite-accept-title" className="text-gradient" style={{ fontSize: '20px', display: 'flex', alignItems: 'center', gap: '8px' }}>
          <UserPlus size={20} color="var(--rose-gold-primary)" />
          {state.kind === 'checking' || state.kind === 'invalid' ? t('inviteAccept.heading') : t('inviteAccept.title', { name })}
        </h2>

        {state.kind === 'checking' && <p style={{ color: 'var(--text-muted)' }}>{t('inviteAccept.checking')}</p>}
        {state.kind === 'invalid' && <p style={{ color: '#e87c8a' }}>{t('inviteAccept.invalid')}</p>}
        {(state.kind === 'confirm' || state.kind === 'joining' || state.kind === 'error') && (
          <p style={{ color: 'var(--text-muted)' }}>{t('inviteAccept.body', { name })}</p>
        )}
        {state.kind === 'error' && <p role="alert" style={{ color: '#e87c8a', fontSize: '13px' }}>{state.message}</p>}

        <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end', flexWrap: 'wrap' }}>
          {state.kind === 'invalid' || state.kind === 'error' ? (
            <button type="button" className="btn-secondary" onClick={close}>{t('inviteAccept.close')}</button>
          ) : (
            <>
              <button type="button" className="btn-secondary" onClick={close} disabled={state.kind === 'joining'}>
                {t('inviteAccept.notNow')}
              </button>
              <button
                type="button"
                className="btn-rose"
                onClick={() => void join(name)}
                disabled={state.kind !== 'confirm'}
              >
                {t('inviteAccept.join')}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

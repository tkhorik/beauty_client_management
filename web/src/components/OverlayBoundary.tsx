import { Component, Suspense, useId, type ComponentType, type ErrorInfo, type LazyExoticComponent } from 'react';
import { useAppTranslation } from '../i18n/LocaleProvider';
import { useDismiss } from './useDismiss';
import { ChunkLoadFailure } from './chunkLoadFailure';

export type OverlayProps = { onClose: () => void };
export type Loader<P> = () => Promise<{ default: ComponentType<P> }>;
export type OverlayContent<P> = { current: () => LazyExoticComponent<ComponentType<P>>; retry: () => void };

function OverlayStatus({ onClose, onRetry, crashed }: OverlayProps & { onRetry?: () => void; crashed?: boolean }) {
  const { t } = useAppTranslation();
  const dismiss = useDismiss(onClose);
  const titleId = useId();
  const hintId = useId();
  const failed = onRetry || crashed;
  return <div className="dialog-overlay" {...dismiss}>
    <section className="dialog-panel" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <header className="dialog-header">
        <h2 id={titleId}>{t(failed ? 'overlay.failedTitle' : 'common.loading')}</h2>
        <button type="button" className="icon-button" aria-label={t('common.close')} onClick={onClose}>×</button>
      </header>
      {failed && <div className="dialog-body">
        <p role="alert">{t(onRetry ? 'overlay.failedBody' : 'overlay.crashedBody')}</p>
        {onRetry && <p id={hintId} style={{ marginTop: 12, color: 'var(--text-muted)', fontSize: 13 }}>{t('overlay.reloadHint')}</p>}
      </div>}
      <footer className="dialog-footer">
        <button type="button" className="btn-secondary" onClick={onClose}>{t('common.close')}</button>
        {onRetry && <button type="button" className="btn-secondary" aria-describedby={hintId} onClick={() => window.location.reload()}>{t('overlay.reload')}</button>}
        {onRetry && <button type="button" className="btn-rose" onClick={onRetry}>{t('clients.retry')}</button>}
      </footer>
    </section>
  </div>;
}

type Failure = 'chunk' | 'render' | null;

export class OverlayBoundary<P extends OverlayProps> extends Component<{ content: OverlayContent<P>; componentProps: P }, { failure: Failure }> {
  state: { failure: Failure } = { failure: null };

  static getDerivedStateFromError(error: unknown): { failure: Failure } {
    return { failure: error instanceof ChunkLoadFailure ? 'chunk' : 'render' };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    if (!(error instanceof ChunkLoadFailure)) console.error('Overlay crashed while rendering:', error, info.componentStack);
  }

  private retry = () => {
    // Browsers may also cache import failures; explicit page reload is offered
    // separately because it discards unsaved form state.
    this.props.content.retry();
    this.setState({ failure: null });
  };

  render() {
    const { onClose } = this.props.componentProps;
    // Retrying cannot fix a render error: the same code would throw again.
    if (this.state.failure === 'render') return <OverlayStatus onClose={onClose} crashed />;
    if (this.state.failure === 'chunk') return <OverlayStatus onClose={onClose} onRetry={this.retry} />;
    const Content = this.props.content.current();
    return <Suspense fallback={<OverlayStatus onClose={onClose} />}><Content {...this.props.componentProps} /></Suspense>;
  }
}

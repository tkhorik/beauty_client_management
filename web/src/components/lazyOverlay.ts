import { createElement, lazy, type ComponentType, type LazyExoticComponent } from 'react';
import { ChunkLoadFailure } from './chunkLoadFailure';
import { OverlayBoundary, type Loader, type OverlayContent, type OverlayProps } from './OverlayBoundary';

/** Keep each overlay's failure isolated from the directory and sibling forms. */
export function lazyOverlay<P extends OverlayProps>(loader: Loader<P>) {
  const load = () => loader().catch((error: unknown) => { throw new ChunkLoadFailure(error); });
  // One lazy instance per overlay, shared by every mount: once its chunk has
  // loaded, reopening renders at once instead of suspending on a new promise.
  let component: LazyExoticComponent<ComponentType<P>> = lazy(load);
  const content: OverlayContent<P> = {
    current: () => component,
    // React.lazy remembers a rejected promise; only a new instance retries the loader.
    retry: () => { component = lazy(load); },
  };
  return function RecoverableOverlay(props: P) {
    return createElement(OverlayBoundary<P>, { content, componentProps: props });
  };
}

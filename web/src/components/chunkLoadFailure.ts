/** Marks a rejected chunk import, so render errors are not reported as network problems. */
export class ChunkLoadFailure extends Error {
  constructor(cause: unknown) {
    super('Overlay chunk failed to load', { cause });
    this.name = 'ChunkLoadFailure';
  }
}

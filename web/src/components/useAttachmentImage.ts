import { useEffect, useState } from 'react';
import { api } from '../services/api';

/** Keep authenticated image downloads independent of the visit-history request. */
export function useAttachmentImage(fileUrl?: string) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{ fileUrl: string; attempt: number; src?: string; failed?: boolean }>();
  const authenticated = fileUrl?.startsWith('/api/attachments/');

  useEffect(() => {
    if (!fileUrl || !authenticated) return;
    const controller = new AbortController();
    let objectUrl: string | undefined;
    void api.loadAttachmentUrl(fileUrl, controller.signal).then(url => {
      if (controller.signal.aborted) {
        URL.revokeObjectURL(url);
        return;
      }
      objectUrl = url;
      setResult({ fileUrl, attempt, src: url });
    }).catch(() => {
      if (!controller.signal.aborted) setResult({ fileUrl, attempt, failed: true });
    });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [fileUrl, authenticated, attempt]);

  const current = result && result.fileUrl === fileUrl && result.attempt === attempt ? result : undefined;
  return {
    src: current?.failed ? undefined : authenticated ? current?.src : fileUrl,
    failed: !fileUrl || current?.failed === true,
    onError: () => { if (fileUrl) setResult({ fileUrl, attempt, failed: true }); },
    retry: () => setAttempt(value => value + 1),
  };
}

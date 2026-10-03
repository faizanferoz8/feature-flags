import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { tokenStore } from './api';
import { followStream } from './sse';

/**
 * Keeps the console current with an environment. Every change anyone commits arrives
 * here as an event, and the flag queries are refetched, so two people looking at the
 * same flag see the same thing without reloading.
 */
export function useEnvironmentStream(environment: string | undefined) {
  const queryClient = useQueryClient();
  const [live, setLive] = useState(false);
  const [revision, setRevision] = useState<number | null>(null);

  useEffect(() => {
    const token = tokenStore.get();
    if (!environment || !token) return;
    const controller = new AbortController();
    let first = true;
    void followStream(
      `/api/environments/${environment}/stream`,
      token,
      controller.signal,
      (event) => {
        if (event.name !== 'put') return;
        setRevision(Number(event.id));
        // The first event is the state we already loaded; later ones are changes.
        if (!first) void queryClient.invalidateQueries({ queryKey: ['flags'] });
        first = false;
      },
      setLive,
    );
    return () => {
      controller.abort();
      setLive(false);
    };
  }, [environment, queryClient]);

  return { live, revision };
}

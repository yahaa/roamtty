import { Token, TokenStore } from "./token";

/** How long an access token stays valid. */
export const ACCESS_TTL_MS = 15 * 60 * 1000;

export interface Session {
  userId: string;
  token: Token;
}

export function isExpired(token: Token, now: number): boolean {
  return token.exp <= now;
}

/**
 * Returns the session for a token, refreshing it once when it has
 * expired. A revoked token never yields a session.
 */
export async function resolveSession(
  store: TokenStore,
  token: Token,
  now = Date.now(),
): Promise<Session | null> {
  if (await store.isRevoked(token.id)) return null;
  if (isExpired(token, now)) {
    return refresh(store, token);
  }
  return { userId: token.sub, token };
}

async function refresh(store: TokenStore, token: Token): Promise<Session | null> {
  const next = await store.exchange(token.refreshId);
  if (!next) return null;
  return { userId: next.sub, token: next };
}

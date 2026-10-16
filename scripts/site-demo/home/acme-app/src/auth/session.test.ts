import { describe, expect, it } from "vitest";
import { resolveSession } from "./session";
import { memoryStore, tokenFor } from "./testing";

describe("resolveSession", () => {
  it("refreshes an expired token", async () => {
    const store = memoryStore();
    const session = await resolveSession(store, tokenFor("ada", { exp: 0 }));
    expect(session?.userId).toBe("ada");
  });

  it("rejects a revoked token", async () => {
    const store = memoryStore({ revoked: ["t1"] });
    expect(await resolveSession(store, tokenFor("ada", { id: "t1" }))).toBeNull();
  });
});

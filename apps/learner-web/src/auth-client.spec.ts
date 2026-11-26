import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthClient } from '../../../packages/api-contracts/auth-client';
describe('shared browser auth client',()=>{
  beforeEach(()=>{vi.restoreAllMocks();vi.stubGlobal('navigator',{});});
  it('coalesces concurrent refresh requests and uses cookie CSRF',async()=>{
    const f=vi.fn(async (input:RequestInfo|URL, init?:RequestInit)=>{
      if(String(input).endsWith('/csrf'))return new Response(JSON.stringify({token:'csrf'}),{status:200});
      expect(init?.credentials).toBe('same-origin');
      expect((init?.headers as Record<string,string>)['X-CSRF-TOKEN']).toBe('csrf');
      return new Response(JSON.stringify({accessToken:'access',expiresIn:600}),{status:200});
    });
    vi.stubGlobal('fetch',f);
    const client=new AuthClient();
    await Promise.all([client.refresh(),client.refresh(),client.refresh()]);
    expect(f.mock.calls.filter(c=>String(c[0]).endsWith('/refresh'))).toHaveLength(1);
  });
});

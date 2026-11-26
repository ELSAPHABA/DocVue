// Shared browser client. Access token is memory-only; refresh and CSRF cookies are HttpOnly.
export type Role = 'LEARNER' | 'CREATOR' | 'TENANT_ADMIN';
export interface Profile { id:string; email:string; emailVerified:boolean; creatorApproved:boolean; platformRole:string }
export interface Tenant { id:string; slug:string; name:string; status:string; version:number; role:Role }
export interface Member { userId:string; role:Role; status:'ACTIVE'|'REVOKED'; version:number }
export class ApiFailure extends Error {
  constructor(readonly status:number, readonly code:string) { super(code); }
}
async function read<T>(r:Response):Promise<T> {
  if (!r.ok) {
    const p = await r.json().catch(() => ({})) as {code?:string};
    throw new ApiFailure(r.status,p.code || 'REQUEST_FAILED');
  }
  return (r.status===204||r.status===202) ? undefined as T : await r.json() as T;
}
export class AuthClient {
  private access = '';
  private pending?:Promise<void>;
  private csrfPending?:Promise<string>;
  private async csrf():Promise<string> {
    if (!this.csrfPending) {
      this.csrfPending=fetch('/api/v1/auth/csrf',{credentials:'same-origin',cache:'no-store'})
        .then(r=>read<{token:string}>(r)).then(r=>r.token).finally(()=>{this.csrfPending=undefined});
    }
    return this.csrfPending;
  }
  private async cookiePost<T>(path:string,body?:object,bearer=false):Promise<T> {
    const token=await this.csrf();
    return read<T>(await fetch('/api/v1/auth/'+path,{method:'POST',credentials:'same-origin',cache:'no-store',
      headers:{'X-CSRF-TOKEN':token,'Content-Type':'application/json',...(bearer?{Authorization:'Bearer '+this.access}:{})},body:body===undefined?undefined:JSON.stringify(body)}));
  }
  private async lock<T>(fn:()=>Promise<T>):Promise<T> {
    // Same-origin tabs can serialize cookie rotation; strict replay policy still applies across distinct origins.
    return navigator.locks ? await navigator.locks.request('dockvue-auth-cookie',fn) : await fn();
  }
  register(email:string,password:string) {return this.cookiePost<void>('register',{email,password});}
  resend(email:string) {return this.cookiePost<void>('resend-verification',{email});}
  async verify(code:string) {if(!this.access)await this.refresh();return this.cookiePost<void>('verify-email',{code},true);}
  async login(email:string,password:string) {
    await this.lock(async()=>{this.access=(await this.cookiePost<{accessToken:string}>('login',{email,password})).accessToken});
  }
  refresh():Promise<void> {
    if(!this.pending) {
      this.pending=this.lock(async()=>{
        try { this.access=(await this.cookiePost<{accessToken:string}>('refresh')).accessToken; }
        catch(e){this.access='';throw e;}
      }).finally(()=>{this.pending=undefined});
    }
    return this.pending;
  }
  async logout() {
    await this.lock(async()=>{try{await this.cookiePost<void>('logout')}finally{this.access=''}});
  }
  async request<T>(path:string,method='GET',body?:object,headers:Record<string,string>={}):Promise<T> {
    if(!this.access) await this.refresh();
    const sent=this.access;
    const call=()=>fetch('/api/v1'+path,{method,credentials:'same-origin',cache:'no-store',
      headers:{...headers,Authorization:'Bearer '+this.access,...(body?{'Content-Type':'application/json'}:{})},
      body:body?JSON.stringify(body):undefined});
    let r=await call();
    if(r.status===401){if(this.access===sent||!this.access)await this.refresh();r=await call()}
    return read<T>(r);
  }
  me(){return this.request<Profile>('/me')}
  tenants(){return this.request<Tenant[]>('/tenants')}
  create(name:string,slug:string,key:string){return this.request<Tenant>('/tenants','POST',{name,slug},{'Idempotency-Key':key})}
  accept(code:string){return this.request<Tenant>('/invitations/accept','POST',{code})}
  members(id:string){return this.request<Member[]>('/tenants/'+id+'/members')}
  invite(id:string,email:string,role:Role){return this.request('/tenants/'+id+'/invitations','POST',{email,role})}
  change(id:string,m:Member,role:Role,status:Member['status']) {
    return this.request<Member>('/tenants/'+id+'/members/'+m.userId,'PUT',{role,status},{'If-Match':'"'+m.version+'"'})
  }
}

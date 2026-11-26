import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test';
import { randomUUID, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import mysql from 'mysql2/promise';
const core=process.env.DV_CORE_URL!,admin=process.env.DV_ADMIN_URL!,learner=process.env.DV_LEARNER_URL!,mail=process.env.DV_MAIL_URL!;
const project=process.env.COMPOSE_PROJECT_NAME||'dockvue';
const uid=randomUUID().slice(0,8),email='vod-'+uid+'@example.invalid',password='DockVue-test-'+randomUUID();
const title='VOD Lab '+uid;
const metadata=(visibility='PUBLIC')=>({title,description:'Phase 04 synthetic fixture',visibility,difficulty:'BEGINNER',durationMs:10000,tags:['vod'],stack:['ffmpeg']});
const phases=[{title:'Start',startMs:0,endMs:4000,objective:'Watch',prerequisites:'',estimatedMinutes:1,startState:'',completionCriteria:''},{title:'Second chapter',startMs:4000,endMs:9000,objective:'Seek',prerequisites:'',estimatedMinutes:1,startState:'',completionCriteria:''}];
async function fixture(sql:string){
 if(process.env.DOCKVUE_TEST_ALLOW_DB_FIXTURES!=='1'||(project!=='dockvue-phase04-test'&&process.env.CI!=='true'))throw new Error('Refusing fixture outside disposable stack');
 if(process.env.DV_INTERNAL_CORE_URL){
  const db=await mysql.createConnection({host:'mysql',user:'dockvue',password:process.env.MYSQL_PASSWORD,database:'dockvue',connectTimeout:5000});
  try{const [rows]=await db.query(sql);return Array.isArray(rows)?rows.map(row=>Object.values(row).join('\t')).join('\n'):''}finally{await db.end()}
 }
 return execFileSync('docker',['compose','-p',project,'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u dockvue -N -B dockvue -e "$1"','sh',sql],{encoding:'utf8'}).trim();
}
async function authPost(ctx:APIRequestContext,path:string,data?:object,bearer?:string){
 const csrf=await (await ctx.get(core+'/api/v1/auth/csrf')).json();
 return ctx.post(core+'/api/v1/auth/'+path,{data,headers:{Origin:process.env.DV_AUTH_ORIGIN!,'X-CSRF-TOKEN':csrf.token,...(bearer?{Authorization:'Bearer '+bearer}:{})}});
}
async function verification(ctx:APIRequestContext){
 let value='';
 await expect.poll(async()=>{
   const body=await (await ctx.get(mail+'/api/v1/messages')).json();
   const msg=body.messages.find((m:any)=>m.Subject==='DockVue email verification'&&m.To.some((t:any)=>t.Address===email));
   if(msg){const d=await(await ctx.get(mail+'/api/v1/message/'+msg.ID)).json();value=d.Text.match(/^[A-Za-z0-9_-]{43}\r?$/m)?.[0].trim()||''}
   return value.length;
 },{timeout:30000}).toBe(43);
 return value;
}
async function loginUI(page:Page){
 await page.getByLabel('이메일',{exact:true}).fill(email);await page.getByLabel('비밀번호',{exact:true}).fill(password);
 await page.getByRole('button',{name:'로그인',exact:true}).click();await expect(page.getByText(email,{exact:false})).toBeVisible();
}
test('multipart resume, real FFmpeg HLS playback, fencing and private access (headless)',async({browser},testInfo)=>{
 test.setTimeout(240000);
 const direct=await request.newContext(), guest=await request.newContext();
 const a=await browser.newContext({viewport:{width:1440,height:1000}}), b=await browser.newContext({viewport:{width:1440,height:1000}});
 try{
  const c=await a.newPage(),l=await b.newPage();
  const file=readFileSync('tests/fixtures/generated/sample.mp4');
  const sha=createHash('sha256').update(file).digest('hex');
  expect(file.length).toBeGreaterThan(16*1024*1024);
  expect((await authPost(direct,'register',{email,password})).status()).toBe(202);
  const lr=await authPost(direct,'login',{email,password});expect(lr.status()).toBe(200);
  const token=(await lr.json()).accessToken;const headers={Authorization:'Bearer '+token};
  expect((await authPost(direct,'verify-email',{code:await verification(direct)},token)).status()).toBe(204);
  const owner=(await(await direct.get(core+'/api/v1/me',{headers})).json()).id;
  await fixture("UPDATE users SET creator_approved=TRUE WHERE id='"+owner+"'");
  const tr=await direct.post(core+'/api/v1/tenants',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{name:'VOD '+uid,slug:'vod-'+uid}});expect(tr.status()).toBe(201);
  const tenant=(await tr.json()).id;
  const vr=await direct.post(core+'/api/v1/videos',{headers:{...headers,'Idempotency-Key':randomUUID()},data:{tenantId:tenant,metadata:metadata()}});expect(vr.status()).toBe(201);
  let d=await vr.json(),id=d.video.id,path=core+'/api/v1/tenants/'+tenant+'/videos/'+id;
  const update=async(suffix:string,data:object)=>{const r=await direct.put(path+suffix,{headers:{...headers,'If-Match':'"'+d.video.version+'"'},data});expect(r.status()).toBe(200);d=await r.json()};
  await update('/timeline',{phases});await update('/preview',{enabled:true});
  expect((await guest.get(core+'/api/v1/videos/'+id+'/playback')).status()).toBe(404);
  const body={filename:'sample.mp4',bytes:file.length,sha256:sha},key=randomUUID();
  const start=()=>direct.post(path+'/uploads',{headers:{...headers,'Idempotency-Key':key},data:body});
  const sr=await start();expect(sr.status()).toBe(201);const session=await sr.json(),upload=path+'/uploads/'+session.id;
  expect((await(await start()).json()).id).toBe(session.id);
  expect((await direct.post(path+'/uploads',{headers:{...headers,'Idempotency-Key':key},data:{...body,bytes:body.bytes+1}})).status()).toBe(409);
  expect((await direct.post(upload+'/complete',{headers})).status()).toBe(422);
  const part=file.subarray(0,session.partSize),checksum=createHash('sha256').update(part).digest('base64');
  const signed=await direct.post(upload+'/parts',{headers,data:{partNumber:1,checksum}});expect(signed.status()).toBe(200);
  const url=(await signed.json()).url;
  const sent=await guest.put(url,{headers:{'x-amz-checksum-sha256':checksum,'Content-Type':'application/octet-stream'},data:part});expect(sent.ok()).toBeTruthy();
  const listed=await(await direct.get(upload,{headers})).json();expect(listed.parts).toHaveLength(1);expect(listed.parts[0].bytes).toBe(session.partSize);
  await c.goto(admin);await loginUI(c);
  await c.evaluate(({owner,tenant,id,record})=>localStorage.setItem('dockvue-upload:'+JSON.stringify([owner,tenant,id]),JSON.stringify(record)),{owner,tenant,id,record:{id:session.id,key,sha256:sha,bytes:file.length,filename:'sample.mp4'}});
  await c.getByTestId('tenant-select').selectOption(tenant);
  await c.getByRole('button',{name:new RegExp(title)}).click();
  await c.getByTestId('media-file').setInputFiles({name:'sample.mp4',mimeType:'video/mp4',buffer:file});
  await c.getByTestId('media-upload').click();
  await expect(c.getByTestId('media-status')).toHaveText('SUCCEEDED',{timeout:120000});
  const state=await(await direct.get(path+'/media',{headers})).json();
  expect(state.status).toBe('SUCCEEDED');expect(state.durationMs).toBeGreaterThanOrEqual(9900);expect(state.durationMs).toBeLessThanOrEqual(10100);
  expect(await fixture("SELECT COUNT(*) FROM jobs WHERE media_version_id='"+state.mediaId+"'")).toBe('1');
  expect(await fixture("SELECT state FROM jobs WHERE id='"+state.jobId+"'")).toBe('SUCCEEDED');
  expect(await fixture("SELECT delivery_state FROM outbox_events WHERE aggregate_id='"+state.jobId+"'")).toBe('SENT');
  expect((await direct.post(upload+'/complete',{headers})).status()).toBe(200);
  const workerToken=process.env.MEDIA_WORKER_TOKEN||'dockvue-local-worker-only-not-for-production';
  const internal=core+'/internal/media/jobs/'+state.jobId;
  expect((await guest.post(internal+'/claim')).status()).toBe(401);
  expect((await guest.post(internal+'/claim',{headers:{'X-Worker-Token':workerToken}})).status()).toBe(204);
  expect((await guest.post(internal+'/heartbeat',{headers:{'X-Worker-Token':workerToken},data:{fence:1}})).status()).toBe(409);
  await c.screenshot({path:testInfo.outputPath('creator-media.png'),fullPage:true});
  const play=await guest.get(core+'/api/v1/videos/'+id+'/playback');expect(play.status()).toBe(200);
  const playback=await play.json();expect(playback.mediaId).toBe(state.mediaId);
  const master=await guest.get(core+playback.url);expect(master.status()).toBe(200);const masterText=await master.text();
  expect(masterText).toContain('v360/index.m3u8');expect(masterText).toContain('v720/index.m3u8');
  const variant=await guest.get(core+playback.url.replace('master.m3u8','v720/index.m3u8'));expect(variant.status()).toBe(200);expect(await variant.text()).toContain('#EXT-X-ENDLIST');
  await l.goto(learner+'/videos/'+id);
  const player=l.getByTestId('media-player');await expect(player).toBeVisible();
  await expect.poll(()=>player.evaluate((v:HTMLVideoElement)=>v.readyState),{timeout:30000}).toBeGreaterThanOrEqual(2);
  await player.evaluate(async(v:HTMLVideoElement)=>{v.muted=true;await v.play()});
  await expect.poll(()=>player.evaluate((v:HTMLVideoElement)=>v.currentTime),{timeout:15000}).toBeGreaterThan(0.5);
  await player.evaluate((v:HTMLVideoElement)=>v.pause());
  await l.getByRole('button',{name:'0:04 · Second chapter'}).click();
  await expect.poll(()=>player.evaluate((v:HTMLVideoElement)=>v.currentTime)).toBeGreaterThanOrEqual(3.9);
  await l.screenshot({path:testInfo.outputPath('learner-hls.png'),fullPage:true});
  await loginUI(l);
  await expect(l.getByTestId('media-player')).toBeVisible();
  await expect.poll(()=>l.getByTestId('media-player').evaluate((v:HTMLVideoElement)=>v.readyState),{timeout:30000}).toBeGreaterThanOrEqual(1);
  await l.getByRole('button',{name:'0:04 · Second chapter'}).click();
  await l.getByTestId('media-player').evaluate(async(v:HTMLVideoElement)=>{v.muted=true;await v.play();v.pause()});
  await expect.poll(async()=>{const r=await direct.get(core+'/api/v1/videos/'+id+'/playback-progress',{headers});return (await r.json()).positionMs},{timeout:15000}).toBeGreaterThanOrEqual(3900);
  d=await(await direct.get(path,{headers})).json();
  await update('',{...metadata('PRIVATE'),durationMs:state.durationMs});
  expect((await guest.get(core+'/api/v1/videos/'+id+'/playback')).status()).toBe(404);
  expect((await guest.get(core+playback.url)).status()).toBe(404);
  expect((await direct.get(core+'/api/v1/videos/'+id+'/playback',{headers})).status()).toBe(200);
  console.log('PASS real multipart resume (2 parts), outbox, transcode, duplicate claim/fence, HLS actual playback/seek/progress, private denial');
 }finally{await a.close();await b.close();await direct.dispose();await guest.dispose()}
});

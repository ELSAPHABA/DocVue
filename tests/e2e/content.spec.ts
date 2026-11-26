import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import mysql from 'mysql2/promise';
const core=process.env.DV_CORE_URL!, admin=process.env.DV_ADMIN_URL!, learner=process.env.DV_LEARNER_URL!, mail=process.env.DV_MAIL_URL!;
const project=process.env.COMPOSE_PROJECT_NAME||'dockvue';
const uid=randomUUID().slice(0,8), title='Docker Lab '+uid, pass='DockVue-test-'+randomUUID();
const creatorEmail='creator-'+uid+'@example.invalid', learnerEmail='learner-'+uid+'@example.invalid';
const metadata=(name:string,visibility='PUBLIC')=>({title:name,description:'Reproducible backend training',visibility,difficulty:'BEGINNER',durationMs:120000,tags:['backend'],stack:['docker','spring']});
const phase=(name:string,startMs=0,endMs=60000)=>({title:name,startMs,endMs,objective:'Understand services',prerequisites:'Docker',estimatedMinutes:10,startState:'Starter project',completionCriteria:'Tests pass'});
async function fixture(sql:string){
 if(process.env.DOCKVUE_TEST_ALLOW_DB_FIXTURES!=='1'||(!['dockvue-phase03-test','dockvue-phase04-test'].includes(project)&&process.env.CI!=='true'))throw new Error('Refusing fixture outside disposable test stack');
 if(process.env.DV_INTERNAL_CORE_URL){
   const db=await mysql.createConnection({host:'mysql',user:'dockvue',password:process.env.MYSQL_PASSWORD,database:'dockvue',connectTimeout:5000});
   try{const [rows]=await db.query(sql);return Array.isArray(rows)?rows.map(row=>Object.values(row).join('\t')).join('\n'):''}
   finally{await db.end()}
 }
 return execFileSync('docker',['compose','-p',project,'--profile','core','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u dockvue -N -B dockvue -e "$1"','sh',sql],{encoding:'utf8'}).trim();
}
async function code(email:string,purpose:string){
 const context=await request.newContext();
 try{
   let found='';
   await expect.poll(async()=>{
     const res=await context.get(mail+'/api/v1/messages');const body=await res.json();
     const msg=body.messages.find((m:any)=>m.Subject==='DockVue '+purpose&&m.To.some((t:any)=>t.Address===email));
     if(msg){const detail=await (await context.get(mail+'/api/v1/message/'+msg.ID)).json();found=(detail.Text as string).match(/^[A-Za-z0-9_-]{43}\r?$/m)?.[0].trim()||''}
     return found.length;
   },{timeout:30000}).toBe(43);
   return found;
 }finally{await context.dispose()}
}
async function authPost(context:APIRequestContext,path:string,data?:object,bearer?:string){
 const csrf=await (await context.get(core+'/api/v1/auth/csrf')).json();
 return context.post(core+'/api/v1/auth/'+path,{data,headers:{Origin:process.env.DV_AUTH_ORIGIN!,'X-CSRF-TOKEN':csrf.token,...(bearer?{Authorization:'Bearer '+bearer}:{})}});
}
async function login(context:APIRequestContext,email:string){const res=await authPost(context,'login',{email,password:pass});expect(res.status()).toBe(200);return (await res.json()).accessToken as string}
async function registerUI(page:Page,email:string){
 await page.getByLabel('이메일',{exact:true}).fill(email);await page.getByLabel('비밀번호',{exact:true}).fill(pass);
 await page.getByRole('button',{name:'가입',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'완료'})).toBeVisible();
 await page.getByLabel('비밀번호',{exact:true}).fill(pass);
 await page.getByRole('button',{name:'로그인',exact:true}).click();
 await expect(page.getByText(email,{exact:false})).toBeVisible();
 await page.getByLabel('이메일 인증 코드').fill(await code(email,'email verification'));
 await page.getByRole('button',{name:'이메일 인증',exact:true}).click();
 await expect(page.getByText('인증 완료',{exact:false})).toBeVisible();
}
test('creator workflow, guest catalog, learner progress and access isolation (headless)',async({browser},testInfo)=>{
 test.setTimeout(180000);
 // Separate browser contexts prevent localhost cross-port refresh cookie collisions.
 const creatorContext=await browser.newContext({viewport:{width:1440,height:1000}});
 const learnerContext=await browser.newContext({viewport:{width:1440,height:1000}});
 const direct=await request.newContext();
 const guest=await request.newContext();
 try{
  const c=await creatorContext.newPage(), l=await learnerContext.newPage();
  const pageErrors:string[]=[];c.on('pageerror',e=>pageErrors.push(e.message));l.on('pageerror',e=>pageErrors.push(e.message));
  await c.goto(admin);await registerUI(c,creatorEmail);
  const creatorId=await fixture("SELECT id FROM users WHERE normalized_email='"+creatorEmail+"'");
  expect(creatorId).toMatch(/^[0-9a-f-]{36}$/);
  await fixture("UPDATE users SET creator_approved=TRUE,version=version+1 WHERE id='"+creatorId+"'");
  await c.getByRole('button',{name:'채널 새로고침'}).click();
  await expect(c.getByText('승인됨',{exact:false})).toBeVisible();
  await c.getByLabel('채널 이름').fill('Lab '+uid);await c.getByLabel('채널 슬러그').fill('lab-'+uid);
  await c.getByRole('button',{name:'채널 생성'}).click();
  await expect(c.getByTestId('tenant-select').locator('option').filter({hasText:'Lab '+uid})).toHaveCount(1);
  const tenant=await fixture("SELECT id FROM tenants WHERE slug='lab-"+uid+"'");
  await c.getByTestId('tenant-select').selectOption(tenant);
  await c.getByRole('button',{name:'새 영상',exact:true}).click();
  await c.getByTestId('video-title').fill(title);
  await c.getByLabel('영상 설명').fill('Learn Docker Compose and Spring');
  await c.getByLabel('공개 범위').selectOption('PUBLIC');
  await c.getByLabel('예상 영상 길이 (ms)').fill('120000');
  await c.getByLabel('태그 (쉼표 구분)').fill('backend');
  await c.getByLabel('기술 스택 (쉼표 구분)').fill('docker, spring');
  await c.getByRole('button',{name:'메타데이터 저장'}).click();
  await expect(c.getByRole('heading',{name:'영상 편집'})).toBeVisible();
  const id=await fixture("SELECT id FROM videos WHERE tenant_id='"+tenant+"' AND title='"+title+"'");
  expect(await fixture("SELECT COUNT(*) FROM video_repositories WHERE video_id='"+id+"'")).toBe('1');
  expect((await guest.get(core+'/api/v1/videos/'+id)).status()).toBe(404);
  await c.getByRole('button',{name:'Phase 추가'}).click();
  await c.getByTestId('phase-0').getByLabel('Phase 제목').fill('Start services');
  await c.getByTestId('phase-0').getByLabel('학습 목표').fill('Start the database');
  await c.getByRole('button',{name:'Phase 추가'}).click();
  await c.getByTestId('phase-1').getByLabel('Phase 제목').fill('Check health');
  await c.getByTestId('phase-1').getByLabel('시작 (ms)').fill('50000');
  await c.getByRole('button',{name:'타임라인 저장'}).click();
  await expect(c.getByText('Phase 제목, 시간 순서·중첩 및 예상 소요 시간을 확인하세요.')).toBeVisible();
  await c.getByTestId('phase-1').getByLabel('시작 (ms)').fill('60000');
  await c.getByRole('button',{name:'타임라인 저장'}).click();
  await expect(c.getByRole('button',{name:'메타데이터 미리보기 공개'})).toBeEnabled();
  await c.getByRole('button',{name:'메타데이터 미리보기 공개'}).click();
  await expect(c.getByRole('button',{name:'미리보기 숨기기'})).toBeVisible();
  await c.screenshot({path:testInfo.outputPath('creator-desktop.png'),fullPage:true});
  await l.goto(learner);
  await l.getByLabel('검색',{exact:true}).fill(title);
  await l.getByLabel('기술 스택',{exact:true}).fill('docker');
  await l.getByRole('button',{name:new RegExp(title)}).click();
  await expect(l.getByRole('heading',{name:title})).toBeVisible();
  await expect(l.getByText('영상 재생 준비 중')).toBeVisible();
  await expect(l.getByRole('heading',{name:'Start services'})).toBeVisible();
  await l.screenshot({path:testInfo.outputPath('learner-desktop.png'),fullPage:true});
  await registerUI(l,learnerEmail);
  await l.getByRole('button',{name:'학습 시작 위치 저장'}).first().click();
  await expect(l.getByRole('button',{name:'시작 위치 저장됨'})).toBeVisible();
  await l.reload();await l.getByRole('button',{name:'세션 복원'}).click();
  await expect(l.getByRole('button',{name:'시작 위치 저장됨'})).toBeVisible();
  await l.setViewportSize({width:390,height:844});
  await expect.poll(()=>l.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await l.screenshot({path:testInfo.outputPath('learner-mobile.png'),fullPage:true});
  await c.setViewportSize({width:390,height:844});
  await expect.poll(()=>c.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await c.screenshot({path:testInfo.outputPath('creator-mobile.png'),fullPage:true});
  await c.setViewportSize({width:1440,height:1000});
  // Direct API validations with real auth and the browser's current edited state.
  const access=await login(direct,creatorEmail), headers={Authorization:'Bearer '+access};
  const path=core+'/api/v1/tenants/'+tenant+'/videos/'+id;
  let d=await (await direct.get(path,{headers})).json();
  const version=d.video.version, etag={'If-Match':'"'+version+'"'};
  const bad=await direct.put(path+'/timeline',{headers:{...headers,...etag},data:{phases:[phase('A',0,60000),phase('B',50000,90000)]}});
  expect(bad.status()).toBe(422);
  expect((await direct.put(path,{headers,data:metadata(title)})).status()).toBe(428);
  expect((await direct.put(path,{headers:{...headers,...etag},data:{...metadata(title),durationMs:1}})).status()).toBe(422);
  const updated=await direct.put(path,{headers:{...headers,...etag},data:metadata(title)});
  expect(updated.status()).toBe(200);
  expect((await direct.put(path,{headers:{...headers,...etag},data:metadata(title)})).status()).toBe(412);
  await c.getByTestId('video-title').fill(title+' stale edit');
  await c.getByRole('button',{name:'메타데이터 저장'}).click();
  await expect(c.getByText(/다른 편집에서 변경되었습니다/)).toBeVisible();
  await expect(c.getByTestId('video-title')).toHaveValue(title+' stale edit');
  await l.reload();await l.getByRole('button',{name:'세션 복원'}).click();
  await expect(l.getByText(/이전 시작 위치가 만료되었습니다/)).toBeVisible();
  // Same creation key, conflicting payload, keyset paging, unlisted/private behavior.
  const make=async(name:string,visibility='PUBLIC',key=randomUUID())=>{
    const res=await direct.post(core+'/api/v1/videos',{headers:{...headers,'Idempotency-Key':key},data:{tenantId:tenant,metadata:metadata(name,visibility)}});
    expect(res.status()).toBe(201);let x=await res.json();
    const target=core+'/api/v1/tenants/'+tenant+'/videos/'+x.video.id;
    const tl=await direct.put(target+'/timeline',{headers:{...headers,'If-Match':'"'+x.video.version+'"'},data:{phases:[phase('Prepare')]}});
    expect(tl.status()).toBe(200);x=await tl.json();
    const preview=await direct.put(target+'/preview',{headers:{...headers,'If-Match':'"'+x.video.version+'"'},data:{enabled:true}});
    expect(preview.status()).toBe(200);return await preview.json();
  };
  const second=await make(title+' extra');
  const secret=await make(title+' unlisted','UNLISTED');
  const priv=await make(title+' private','PRIVATE');
  const key=randomUUID(), body={tenantId:tenant,metadata:metadata(title+' idempotent')};
  const create=()=>direct.post(core+'/api/v1/videos',{headers:{...headers,'Idempotency-Key':key},data:body});
  const rs=await Promise.all([create(),create()]);expect(rs.map(r=>r.status())).toEqual([201,201]);const ids=await Promise.all(rs.map(async r=>(await r.json()).video.id));expect(ids[0]).toBe(ids[1]);
  expect((await direct.post(core+'/api/v1/videos',{headers:{...headers,'Idempotency-Key':key},data:{...body,metadata:metadata('other')}})).status()).toBe(409);
  const list=core+'/api/v1/videos?q='+encodeURIComponent(title)+'&stack=docker&tag=backend&difficulty=BEGINNER&limit=1';
  const first=await (await guest.get(list)).json();expect(first.items).toHaveLength(1);expect(first.nextCursor).toBeTruthy();
  const secondPage=await (await guest.get(list+'&cursor='+encodeURIComponent(first.nextCursor))).json();
  expect(secondPage.items).toHaveLength(1);expect(secondPage.items[0].id).not.toBe(first.items[0].id);
  expect(secondPage.nextCursor).toBeNull();
  expect((await guest.get(list+'&cursor='+encodeURIComponent(first.nextCursor+'x'))).status()).toBe(422);
  expect((await guest.get(core+'/api/v1/videos?q=different&cursor='+encodeURIComponent(first.nextCursor))).status()).toBe(422);
  expect((await guest.get(core+'/api/v1/videos/'+secret.video.id)).status()).toBe(200);
  expect((await guest.get(core+'/api/v1/videos/'+priv.video.id)).status()).toBe(404);
  expect((await direct.get(core+'/api/v1/videos/'+priv.video.id,{headers})).status()).toBe(200);
  const outsider=await request.newContext();
  try{
    const token=await login(outsider,learnerEmail);const h={Authorization:'Bearer '+token};
    expect((await outsider.get(core+'/api/v1/videos/'+priv.video.id,{headers:h})).status()).toBe(404);
    expect((await outsider.get(path,{headers:h})).status()).toBe(404);
    expect((await outsider.post(core+'/api/v1/videos',{headers:{...h,'Idempotency-Key':randomUUID()},data:body})).status()).toBe(404);
    expect((await outsider.put(core+'/api/v1/videos/'+id+'/preview-progress',{headers:{...h,'If-Match':'"'+(version+1)+'"'},data:{phaseId:second.phases[0].id,videoVersion:version+1}})).status()).toBe(422);
  }finally{await outsider.dispose()}
  d=await (await direct.get(path,{headers})).json();
  const otherPhase=await direct.put(path+'/timeline',{headers:{...headers,'If-Match':'"'+d.video.version+'"'},data:{phases:[{...phase('foreign'),id:second.phases[0].id}]}});
  expect(otherPhase.status()).toBe(422);
  const archive=await direct.delete(path,{headers:{...headers,'If-Match':'"'+d.video.version+'"'}});expect(archive.status()).toBe(204);
  expect((await guest.get(core+'/api/v1/videos/'+id)).status()).toBe(404);
  expect(pageErrors).toEqual([]);
  console.log('PASS browser registration/login, CMS timeline and preview, search, progress, desktop/mobile, stale editor, cursor, tenant/phase isolation, archive');
 }finally{await creatorContext.close();await learnerContext.close();await direct.dispose();await guest.dispose()}
});

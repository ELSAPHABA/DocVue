import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
import { boundedPosition, fileSha256, partChecksum, uploadFile, uploadStorageKey, MAX_UPLOAD_BYTES, validateFile, type MediaClient } from '../../../packages/api-contracts/media-client';

const data=new Map<string,string>();
beforeEach(()=>{data.clear();vi.stubGlobal('localStorage',{getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value),removeItem:(key:string)=>data.delete(key)})});
afterEach(()=>vi.unstubAllGlobals());

describe('VOD transfer contract',()=>{
  it('hashes the whole file across chunk boundaries and uses base64 for part checksums',async()=>{
    const bytes=new Uint8Array(4*1024**2+17).fill(0xab);const blob=new Blob([bytes]);const updates:number[]=[];
    expect(await fileSha256(blob,undefined,n=>updates.push(n))).toBe(createHash('sha256').update(bytes).digest('hex'));
    expect(updates).toEqual([4*1024**2,bytes.length]);
    expect(await partChecksum(blob.slice(0,17))).toBe(createHash('sha256').update(bytes.slice(0,17)).digest('base64'));
  });
  it('rejects unsupported files and clamps playback positions',()=>{
    expect(()=>validateFile({name:'bad.exe',size:1})).toThrow();expect(()=>validateFile({name:'ok.mp4',size:MAX_UPLOAD_BYTES+1})).toThrow();
    expect(()=>validateFile({name:'ok.MOV',size:MAX_UPLOAD_BYTES})).not.toThrow();
    expect([-10,NaN,10.8,101].map(x=>boundedPosition(x,100))).toEqual([0,0,10,100]);
  });
  it('resumes verified server parts, limits concurrency, and completes once',async()=>{
    const file=new File(['abcdefghijklmnopqrst'],'test.mp4');const key=uploadStorageKey('u','t','v');
    data.set(key,JSON.stringify({id:'upload',key:'key',sha256:await fileSha256(file),filename:file.name,bytes:file.size}));
    const signed:number[]=[];let inFlight=0,peak=0;
    const client={session:vi.fn().mockResolvedValue({id:'upload',partSize:2,status:'OPEN',parts:[{partNumber:1,etag:'etag',bytes:2,checksum:await partChecksum(file.slice(0,2))}]}),
      create:vi.fn(),sign:vi.fn(async(_t:string,_v:string,_id:string,n:number,checksum:string)=>{signed.push(n);return {url:'https://example.test/'+n+'?checksum='+encodeURIComponent(checksum)}}),complete:vi.fn().mockResolvedValue({jobId:'job'})};
    vi.stubGlobal('fetch',vi.fn(async(url:string,init:RequestInit)=>{
      inFlight++;peak=Math.max(peak,inFlight);expect(init.credentials).toBe('omit');expect((init.headers as Record<string,string>)['x-amz-checksum-sha256']).toBe(new URL(url).searchParams.get('checksum'));
      await new Promise(resolve=>setTimeout(resolve,5));inFlight--;return new Response(null,{status:200});
    }));
    const transferred:number[]=[];
    await expect(uploadFile(client as unknown as MediaClient,'u','t','v',file,new AbortController().signal,u=>{if(u.phase==='uploading')transferred.push(u.bytes)})).resolves.toEqual({jobId:'job'});
    expect(client.create).not.toHaveBeenCalled();expect(signed.sort((a,b)=>a-b)).toEqual([2,3,4,5,6,7,8,9,10]);expect(peak).toBeLessThanOrEqual(4);
    expect(transferred.at(-1)).toBe(file.size);expect(client.complete).toHaveBeenCalledOnce();expect(data.has(key)).toBe(false);
  });
  it('retains the resume session on transfer failure and does not complete',async()=>{
    const file=new File(['ab'],'test.mp4'),key=uploadStorageKey('u','t','v');
    const client={create:vi.fn().mockResolvedValue({id:'upload',partSize:2,status:'OPEN'}),sign:vi.fn().mockResolvedValue({url:'https://example.test/1'}),complete:vi.fn()};
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(null,{status:500})));
    await expect(uploadFile(client as unknown as MediaClient,'u','t','v',file,new AbortController().signal,()=>{})).rejects.toThrow('500');
    expect(JSON.parse(data.get(key)!).id).toBe('upload');expect(client.complete).not.toHaveBeenCalled();
  });
});

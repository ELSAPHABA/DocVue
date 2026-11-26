import {describe,it,expect} from 'vitest';
import {emptyPhase,timelineError,query} from '../../../packages/api-contracts/content-client';
describe('content form and search contract',()=>{
 it('allows adjacent ranges but rejects overlapping, reversed and over-duration ranges',()=>{
   const a={...emptyPhase(),title:'A'},b={...emptyPhase(60000),title:'B'};
   expect(timelineError([a,b],120000)).toBe('');
   expect(timelineError([a,{...b,startMs:59999}],120000)).not.toBe('');
   expect(timelineError([a,b],119999)).not.toBe('');
 });
 it('encodes special search text and omits empty filters',()=>{
   expect(query({q:'C++ & docker',stack:'',limit:12})).toBe('?q=C%2B%2B+%26+docker&limit=12');
 });
});

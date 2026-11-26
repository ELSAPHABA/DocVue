<script setup lang="ts">
import { ref } from 'vue';
import { AuthClient, type Profile, type Tenant } from '../../../packages/api-contracts/auth-client';
const client=new AuthClient();
const profile=ref<Profile|null>(null),tenants=ref<Tenant[]>([]),busy=ref(false),message=ref('');
const email=ref(''),password=ref(''),code=ref(''),invite=ref('');
async function reload(){profile.value=await client.me();tenants.value=await client.tenants()}
async function run(action:string){
  if(busy.value)return;busy.value=true;
  try{
    switch(action){
      case 'register':await client.register(email.value,password.value);password.value='';break;
      case 'resend':await client.resend(profile.value?.email || email.value);break;
      case 'verify':await client.verify(code.value);code.value='';if(profile.value)await reload();break;
      case 'login':try{await client.login(email.value,password.value)}finally{password.value=''}await reload();break;
      case 'resume':await client.refresh();await reload();break;
      case 'logout':try{await client.logout()}finally{profile.value=null;tenants.value=[]}break;
      case 'accept':await client.accept(invite.value);invite.value='';await reload();break;
    }
    message.value='완료';
  }catch(e){message.value=e instanceof Error?e.message:'요청 실패'}finally{busy.value=false}
}
</script>
<template>
  <section>
    <h2>계정 및 채널</h2><p role="status">{{ message }}</p>
    <template v-if="!profile">
      <label>이메일 <input v-model="email" type="email" autocomplete="email"></label>
      <label>비밀번호 <input v-model="password" type="password" autocomplete="current-password"></label>
      <button :disabled="busy" @click="run('login')">로그인</button>
      <button :disabled="busy" @click="run('register')">가입</button>
      <button :disabled="busy" @click="run('resume')">세션 복원</button>
      <button :disabled="busy" @click="run('resend')">인증 메일 재전송</button>
    </template>
    <template v-else>
      <p>{{ profile.email }} · 이메일 {{ profile.emailVerified?'인증 완료':'미인증' }}</p>
      <button v-if="!profile.emailVerified" :disabled="busy" @click="run('resend')">인증 메일 재전송</button>
      <button :disabled="busy" @click="run('logout')">로그아웃</button>
      <label>초대 코드 <input v-model="invite" autocomplete="off"></label>
      <button :disabled="busy" @click="run('accept')">초대 수락</button>
      <p v-for="t in tenants" :key="t.id">{{ t.name }} · {{ t.role }}</p>
    </template>
    <template v-if="profile && !profile.emailVerified">
      <label>이메일 인증 코드 <input v-model="code" autocomplete="off"></label>
      <button :disabled="busy" @click="run('verify')">이메일 인증</button>
    </template>
  </section>
</template>
<style scoped>
section{border-top:1px solid #dbe2eb;margin-top:24px;padding-top:16px}
label{display:block;margin-top:12px}input{display:block;padding:8px;max-width:100%;margin:4px 0}button{margin:4px}
</style>

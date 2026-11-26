"""Phase 02 HTTP/DB/Redis/Mailpit integration tests; isolated local/CI stack only."""
import base64
import concurrent.futures
import hashlib
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "dockvue")
INTERNAL = os.environ.get("DOCKVUE_TEST_INTERNAL") == "1"
PREFIX = ["docker", "compose", "-p", PROJECT, "--profile", "core"]
assert os.environ.get("DOCKVUE_TEST_ALLOW_DB_FIXTURES") == "1", "Use a disposable stack and explicitly enable DB fixtures"
assert PROJECT == "dockvue-phase02-test" or os.environ.get("CI") == "true", "Refusing fixtures against normal developer data"


def compose(*args):
    return subprocess.run(PREFIX + list(args), check=True, capture_output=True, text=True, encoding="utf-8", timeout=90).stdout.strip()


def sql(statement):
    if INTERNAL:
        import pymysql
        with pymysql.connect(host="mysql", user="dockvue", password=os.environ["MYSQL_PASSWORD"],
                             database="dockvue", autocommit=True, connect_timeout=5) as connection:
            with connection.cursor() as cursor:
                cursor.execute(statement)
                return "\n".join("\t".join(str(v) for v in row) for row in cursor.fetchall())
    return compose("exec", "-T", "mysql", "sh", "-c",
                   'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u dockvue -N -B dockvue -e "$1"', "sh", statement)


BASE = "http://core-api:8080" if INTERNAL else "http://" + compose("port", "core-api", "8080")
MAIL = "http://mailpit:8025" if INTERNAL else "http://" + compose("port", "mailpit", "8025")
ORIGIN = os.environ["DOCKVUE_TEST_ORIGIN"] if INTERNAL else BASE
SUFFIX = uuid.uuid4().hex[:12]
PASS = "DockVue-test-" + secrets.token_urlsafe(18)


def raw(opener, url, method="GET", data=None, headers=None):
    request = urllib.request.Request(url, data=None if data is None else json.dumps(data).encode(),
                                     method=method, headers=headers or {})
    try:
        with opener.open(request, timeout=30) as r:
            status, h, body = r.status, r.headers, r.read()
    except urllib.error.HTTPError as r:
        status, h, body = r.code, r.headers, r.read()
    return status, h, json.loads(body) if body and "json" in h.get("Content-Type", "") else body


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.access = None
        self.csrf = None
    def call(self, path, expected, method="GET", data=None, bearer=True, auth_post=False, headers=None):
        h = {"Content-Type": "application/json"}
        if bearer and self.access:
            h["Authorization"] = "Bearer " + self.access
        if auth_post:
            self.csrf = self.call("/auth/csrf", 200, bearer=False)[1]["token"]
            h.update({"Origin": ORIGIN, "X-CSRF-TOKEN": self.csrf})
        h.update(headers or {})
        s, response_headers, body = raw(self.opener, BASE+"/api/v1"+path, method, data, h)
        assert s == expected, f"{method} {path}: expected {expected}, got {s} ({body.get('code') if isinstance(body,dict) else 'non-json'})"
        if s >= 400:
            assert isinstance(body, dict) and body.get("traceId") and body.get("code"), (path,s)
        return response_headers, body
    def auth(self, path, expected, data=None, bearer=False, headers=None):
        return self.call("/auth/"+path, expected,"POST",data,bearer,True,headers)
    def login(self,email):
        h,b=self.auth("login",200,{"email":email,"password":PASS})
        self.access=b["accessToken"]
        assert b["expiresIn"]==600
        cookie=h.get("Set-Cookie")
        assert "HttpOnly" in cookie and "SameSite=Lax" in cookie and "Path=/api/v1/auth" in cookie
        assert "refreshToken" not in b
        return self.call("/me",200)[1]
    def cookie(self,name):
        return next(c.value for c in self.jar if c.name==name)


def mail_code(email, purpose):
    opener=urllib.request.build_opener()
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        status,_,data=raw(opener,MAIL+"/api/v1/messages")
        assert status==200
        for msg in data["messages"]:
            if msg.get("Subject")=="DockVue "+purpose and any(t.get("Address")==email for t in msg["To"]):
                _,_,detail=raw(opener,MAIL+"/api/v1/message/"+msg["ID"])
                matches=re.findall(r"(?m)^[A-Za-z0-9_-]{43}$",detail["Text"].replace("\r",""))
                assert len(matches)==1
                return matches[0]
        time.sleep(.5)
    raise AssertionError("Expected test mail not received")


def mark(name): print("PASS "+name,flush=True)


def main():
    a,b,c=Client(),Client(),Client()
    ea,eb,ec=[x+"-"+SUFFIX+"@example.invalid" for x in ("admin","learner","outsider")]
    a.call("/me",401)
    a.call("/auth/register",403,"POST",{"email":ea,"password":PASS},bearer=False,headers={"Origin":ORIGIN})
    a.auth("register",403,{"email":ea,"password":PASS},headers={"Origin":"https://untrusted.invalid"})
    mark("unauthenticated, CSRF and Origin rejection")
    for client,email in [(a,ea),(b,eb),(c,ec)]:
        client.auth("register",202,{"email":email.upper(),"password":PASS})
    a.auth("register",202,{"email":ea,"password":"Other-password-12345"})
    a.auth("login",401,{"email":ea,"password":"incorrect-password"})
    ua,ub,uc=[client.login(email) for client,email in [(a,ea),(b,eb),(c,ec)]]
    assert all(not u["emailVerified"] for u in (ua,ub,uc))
    assert sql("SELECT COUNT(*) FROM users WHERE normalized_email='"+ea+"'")== "1"
    assert sql("SELECT LEFT(password_hash,9) FROM users WHERE id='"+ua["id"]+"'")=="$argon2id"
    mark("normalized unique registration, Argon2id, invalid password and login")
    a.call("/tenants",403,"POST",{"name":"test","slug":"dv-"+SUFFIX},headers={"Idempotency-Key":"unverified"})
    codea=mail_code(ea,"email verification")
    b.auth("verify-email",422,{"code":codea},bearer=True)
    for client,email in [(a,ea),(b,eb),(c,ec)]:
        code=mail_code(email,"email verification")
        client.auth("verify-email",204,{"code":code},bearer=True)
        assert client.call("/me",200)[1]["emailVerified"]
    a.auth("verify-email",422,{"code":codea},bearer=True)
    mark("email verification ownership, single use and unverified gate")
    jwks=raw(a.opener,BASE+"/.well-known/jwks.json")[2]
    assert jwks["keys"] and all("d" not in key and key["kty"]=="RSA" for key in jwks["keys"])
    header,payload,_=a.access.split(".")
    decode=lambda s:json.loads(base64.urlsafe_b64decode(s+"="*(-len(s)%4)))
    assert decode(header)["alg"]=="RS256" and decode(payload)["exp"]-decode(payload)["iat"]==600
    a.call("/me",401,headers={"Authorization":"Bearer "+a.access[:-5]+"AAAAA"})
    mark("RS256 access, public-only JWKS, invalid signature")
    create={"name":"Lab "+SUFFIX,"slug":"dv-"+SUFFIX}
    key={"Idempotency-Key":"create-"+SUFFIX}
    def create_concurrently():
        return raw(urllib.request.build_opener(),BASE+"/api/v1/tenants","POST",create,
                   {"Authorization":"Bearer "+a.access,"Content-Type":"application/json",**key})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        creates=list(pool.map(lambda _:create_concurrently(),range(2)))
    assert all(r[0]==201 for r in creates), "concurrent idempotent create status"
    assert creates[0][2]["id"]==creates[1][2]["id"], "concurrent idempotent create result"
    t=creates[0][2]
    tid=t["id"]
    _,again=a.call("/tenants",201,"POST",create,headers=key)
    assert again["id"]==tid
    a.call("/tenants",409,"POST",dict(create,name="changed"),headers=key)
    assert a.call("/tenants",200)[1][0]["id"]==tid
    b.call("/tenants/"+tid,404)
    b.call("/tenants/"+tid+"/members",404)
    a.call("/tenants/"+tid+"/creator-capability",403)
    a.call("/admin/users/"+ua["id"]+"/creator-approval",403,"PUT",{"approved":True},headers={"If-Match":'"0"'})
    mark("tenant idempotency, IDOR, creator approval and admin privilege denial")
    a.call("/tenants/"+tid+"/invitations",201,"POST",{"email":eb,"role":"LEARNER"})
    invite=mail_code(eb,"tenant invitation")
    c.call("/invitations/accept",404,"POST",{"code":invite})
    b.call("/invitations/accept",200,"POST",{"code":invite})
    b.call("/invitations/accept",404,"POST",{"code":invite})
    b.call("/tenants/"+tid,200)
    b.call("/tenants/"+tid+"/members",403)
    b.call("/tenants/"+tid+"/invitations",403,"POST",{"email":ec,"role":"TENANT_ADMIN"})
    mark("email-bound invitation, replay denial, learner RBAC")
    path="/tenants/"+tid+"/members/"
    a.call(path+ub["id"],428,"PUT",{"role":"CREATOR","status":"ACTIVE"})
    a.call(path+ub["id"],200,"PUT",{"role":"CREATOR","status":"ACTIVE"},headers={"If-Match":'"0"'})
    a.call(path+ub["id"],412,"PUT",{"role":"LEARNER","status":"ACTIVE"},headers={"If-Match":'"0"'})
    a.call(path+ua["id"],409,"PUT",{"role":"LEARNER","status":"ACTIVE"},headers={"If-Match":'"0"'})
    b.call("/tenants/"+tid+"/creator-capability",403)
    # Explicitly authorized disposable test fixture; no public bootstrap admin API.
    sql("UPDATE users SET platform_role='PLATFORM_ADMIN' WHERE id='"+uc["id"]+"'")
    c.call("/tenants/"+tid,404)
    approval="/admin/users/"+ub["id"]+"/creator-approval"
    h,_=c.call(approval,200)
    c.call(approval,204,"PUT",{"approved":True},headers={"If-Match":h["ETag"]})
    c.call(approval,412,"PUT",{"approved":False},headers={"If-Match":h["ETag"]})
    b.call("/tenants/"+tid+"/creator-capability",204)
    h,_=c.call(approval,200)
    c.call(approval,204,"PUT",{"approved":False},headers={"If-Match":h["ETag"]})
    b.call("/tenants/"+tid+"/creator-capability",403)
    a.call(path+ub["id"],200,"PUT",{"role":"CREATOR","status":"REVOKED"},headers={"If-Match":'"1"'})
    b.call("/tenants/"+tid,404)
    sql("UPDATE users SET platform_role='USER' WHERE id='"+uc["id"]+"'")
    mark("optimistic membership update, last-admin guard, platform role isolation, live revocation")
    _,second=a.call("/tenants",201,"POST",{"name":"Race "+SUFFIX,"slug":"race-"+SUFFIX},
                    headers={"Idempotency-Key":"race-"+SUFFIX})
    race_id=second["id"]
    a.call("/tenants/"+race_id+"/invitations",201,"POST",{"email":ec,"role":"TENANT_ADMIN"})
    c.call("/invitations/accept",200,"POST",{"code":mail_code(ec,"tenant invitation")})
    def demote(pair):
        client,user=pair
        return raw(urllib.request.build_opener(),BASE+"/api/v1/tenants/"+race_id+"/members/"+user["id"],"PUT",
                   {"role":"LEARNER","status":"ACTIVE"},
                   {"Authorization":"Bearer "+client.access,"Content-Type":"application/json","If-Match":'"0"'})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        demotions=list(pool.map(demote,[(a,ua),(c,uc)]))
    assert sorted(r[0] for r in demotions)==[200,409], "concurrent last-admin protection"
    assert sql("SELECT COUNT(*) FROM memberships WHERE tenant_id='"+race_id+"' AND role='TENANT_ADMIN' AND status='ACTIVE'")=="1"
    mark("concurrent tenant admin demotion preserves one active admin")
    old=a.cookie("DV_REFRESH")
    a.csrf=a.call("/auth/csrf",200,bearer=False)[1]["token"]
    csrf=a.cookie("DV_CSRF")
    assert sql("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash='"+hashlib.sha256(old.encode()).hexdigest()+"'")=="1"
    assert sql("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash='"+old+"'")=="0"
    def rotate():
        return raw(urllib.request.build_opener(),BASE+"/api/v1/auth/refresh","POST",None,
                   {"Origin":ORIGIN,"X-CSRF-TOKEN":csrf,"Cookie":"DV_CSRF="+csrf+"; DV_REFRESH="+old})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        outcomes=list(pool.map(lambda _:rotate(),range(2)))
    assert sorted(r[0] for r in outcomes)==[200,401], "same token concurrency"
    good=next(r for r in outcomes if r[0]==200)
    bad=next(r for r in outcomes if r[0]==401)
    assert bad[2]["code"]=="REFRESH_REPLAY"
    new=re.search(r"DV_REFRESH=([^;]+)",good[1]["Set-Cookie"])[1]
    s,_,_=raw(urllib.request.build_opener(),BASE+"/api/v1/auth/refresh","POST",None,
              {"Origin":ORIGIN,"X-CSRF-TOKEN":csrf,"Cookie":"DV_CSRF="+csrf+"; DV_REFRESH="+new})
    assert s==401
    assert int(sql("SELECT COUNT(*) FROM refresh_families WHERE user_id='"+ua["id"]+"' AND revoked_at IS NOT NULL"))>=1
    mark("concurrent refresh rotation, replay revokes entire family durably, hashed storage")
    c.auth("logout",204)
    c.auth("refresh",401)
    assert int(sql("SELECT COUNT(*) FROM audit_events WHERE tenant_id='"+tid+"'"))>=5
    assert int(sql("SELECT COUNT(*) FROM audit_events WHERE actor_id='"+ua["id"]+"' AND action='REFRESH_REPLAY'"))>=1
    mark("logout, audit persistence")
    # Rate-limit negative path: existing four login calls consume tokens; eventually bucket rejects.
    denied=False
    for _ in range(14):
        s,h,_=raw(a.opener,BASE+"/api/v1/auth/login","POST",{"email":ea,"password":"bad-password"},
                  {"Origin":ORIGIN,"X-CSRF-TOKEN":csrf,"Content-Type":"application/json"})
        if s==429:
            assert h.get("Retry-After")
            denied=True
            break
        assert s==401
    assert denied
    mark("Redis rate limit and Retry-After")
    if not INTERNAL and os.environ.get("DOCKVUE_TEST_REDIS_OUTAGE")=="1":
        try:
            compose("stop","redis")
            a.auth("login",503,{"email":ea,"password":PASS})
            mark("Redis outage fails closed")
        finally:
            compose("start","redis")
    print("PHASE 02 integration passed",flush=True)


if __name__=="__main__":
    if "--outage-only" in sys.argv:
        Client().auth("login",503,{"email":"outage-"+SUFFIX+"@example.invalid","password":PASS})
        mark("Redis outage fails closed")
    else:
        main()

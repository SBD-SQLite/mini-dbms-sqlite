from harness import *
import traceback
import tempfile
BASE=tempfile.mkdtemp(prefix="sqlike-e2e-")+os.sep
MARK=("row(s)","BEGIN:","COMMIT:","ABORT:","ERROR","OK","created","dropped")
port=[int(os.environ.get('E2E_PORT','25600'))]
def newport(): port[0]+=1; return port[0]
def req(c,sql,timeout=10):
    n=len(c.lines); c.send(sql)
    t=time.time()
    while time.time()-t<timeout:
        new=c.lines[n:]
        for l in new:
            if any(m in l for m in MARK): time.sleep(0.15); return "".join(c.lines[n:])
        time.sleep(0.05)
    return None   # no response (blocked)
def pending(c,sql):
    n=len(c.lines); c.send(sql); return n
def got(c,n,timeout=10):
    t=time.time()
    while time.time()-t<timeout:
        for l in c.lines[n:]:
            if any(m in l for m in MARK): time.sleep(0.15); return "".join(c.lines[n:])
        time.sleep(0.05)
    return None
def check(cond,msg):
    if not cond: raise AssertionError(msg)
def val(c,sql):
    r=req(c,sql); check(r is not None,"no response for "+sql); return r
results=[]
def case(name):
    def deco(f):
        def run(protocol):
            d=BASE+"d_%s_%s"%(f.__name__,protocol); shutil.rmtree(d,ignore_errors=True)
            p=newport(); s=None; cs=[]
            def mk():
                c=client(p); cs.append(c); return c
            t0=time.time()
            try:
                s=server(d,p,protocol)
                rv=f(s,p,mk,d,protocol)
                results.append((name,protocol,"SKIP" if rv=="SKIP" else "LULUS","%.1fs"%(time.time()-t0)))
            except Exception as e:
                results.append((name,protocol,"GAGAL",repr(e)[:300])); traceback.print_exc()
            finally:
                for c in cs:
                    try: c.close()
                    except: pass
                if s:
                    try: s.kill9()
                    except: pass
        run.protocols=None; run.f=f; run.name=name
        ALL.append(run); return run
    return deco
ALL=[]

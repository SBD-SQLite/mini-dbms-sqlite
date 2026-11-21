import subprocess,os,time,threading,signal,shutil,sys,queue
"""Starts the real SQLike server and CLI clients as separate processes (no mocks) for the CCM end-to-end tests.

The compiled classes come from run-e2e.sh through SQLIKE_CLASSES; JAVA_HOME is optional.
"""
CP=os.environ["SQLIKE_CLASSES"]
JAVA=os.path.join(os.environ["JAVA_HOME"],"bin","java") if os.environ.get("JAVA_HOME") else "java"
class Proc:
    def __init__(self,cmd):
        self.p=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        self.q=queue.Queue(); self.lines=[]
        threading.Thread(target=self._r,daemon=True).start()
    def _r(self):
        for l in self.p.stdout:
            self.lines.append(l); self.q.put(l)
    def send(self,s):
        self.p.stdin.write(s+"\n"); self.p.stdin.flush()
    def wait_for(self,pat,timeout=15,start=0):
        t=time.time()
        while time.time()-t<timeout:
            for i in range(start,len(self.lines)):
                if pat in self.lines[i]: return i
            time.sleep(0.05)
        return None
    def text(self,start=0): return "".join(self.lines[start:])
    def kill9(self): self.p.send_signal(signal.SIGKILL); self.p.wait()
    def close(self):
        try: self.p.stdin.close()
        except: pass
        try: self.p.wait(timeout=5)
        except: self.p.kill()
def server(data,port,protocol="lock",extra=()):
    s=Proc([JAVA,"-cp",CP,"sqlike.qp.QpServerMain","--data",data,"--port",str(port),"--protocol",protocol,*extra])
    assert s.wait_for("READY",40) is not None or s.wait_for("ready",1) is not None, "server not ready:\n"+s.text()
    return s
def client(port):
    c=Proc([JAVA,"-cp",CP,"sqlike.qp.CliClient","127.0.0.1",str(port)]); time.sleep(0.6); return c

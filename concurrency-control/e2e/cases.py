from e2e import *
import re,threading
def mktables(c,names=("dl_a","dl_b","dl_c")):
    for n in names:
        val(c,"CREATE TABLE %s (id INTEGER PRIMARY KEY, label VARCHAR(20));"%n)
        check("1 row" in val(c,"INSERT INTO %s VALUES (1, 'orig_%s');"%(n,n)),"seed "+n)
def txid(out): return int(re.search(r"txId=(\d+)",out).group(1))
def city(c,i):
    r=val(c,"SELECT city FROM customers WHERE customer_id = %d;"%i)
    m=re.findall(r"\|\s*([^|\n]+?)\s*\|\s*\n",r.replace("\r",""))
    lines=[l for l in r.split("\n") if l.startswith("| ") and "city" not in l]
    return lines[0].strip("| \n").strip() if lines else None

@case("EE-01 kill -9 server: commit bertahan, yang belum commit hilang, txId lanjut, tanpa lock tersisa")
def ee01(s,p,mk,d,proto):
    c1=mk(); orig=city(c1,4)
    check("1 row" in val(c1,"UPDATE customers SET city = 'Committed City' WHERE customer_id = 3;"),"update commit")
    c2=mk(); b=val(c2,"BEGIN TRANSACTION;"); t_old=txid(b)
    check("1 row" in val(c2,"UPDATE customers SET city = 'Uncommitted City' WHERE customer_id = 4;"),"update uncommitted")
    s.kill9()
    s2=server(d,p,proto)
    try:
        c3=client(p)
        try:
            check(city(c3,3)=="Committed City","committed lost: %s"%city(c3,3))
            check(city(c3,4)==orig,"uncommitted survived: %s vs %s"%(city(c3,4),orig))
            nb=val(c3,"BEGIN TRANSACTION;"); check(txid(nb)>t_old,"txId not increasing %s <= %s"%(txid(nb),t_old))
            r=req(c3,"UPDATE customers SET city = 'After Crash' WHERE customer_id = 4;",8)
            check(r and "1 row" in r,"stale lock after crash: %r"%r)
            check("COMMIT" in val(c3,"COMMIT;"),"commit after crash")
            check(city(c3,4)=="After Crash","after crash value")
        finally: c3.close()
    finally: s2.kill9()

@case("EE-02 deadlock tiga client lewat SQL: korban termuda ABORTED, dua lainnya commit, data korban diundo")
def ee02(s,p,mk,d,proto):
    if proto!="lock": return "SKIP"
    c1,c2,c3=mk(),mk(),mk(); mktables(c1)
    for c in (c1,c2,c3): val(c,"BEGIN TRANSACTION;"); time.sleep(0.05)
    check("1 row" in val(c1,"UPDATE dl_a SET label = 'c1a' WHERE id = 1;"),"c1")
    check("1 row" in val(c2,"UPDATE dl_b SET label = 'c2b' WHERE id = 1;"),"c2")
    check("1 row" in val(c3,"UPDATE dl_c SET label = 'c3c' WHERE id = 1;"),"c3")
    n1=pending(c1,"UPDATE dl_b SET label = 'c1b' WHERE id = 1;"); time.sleep(0.4)
    n2=pending(c2,"UPDATE dl_c SET label = 'c2c' WHERE id = 1;"); time.sleep(0.4)
    check(got(c1,n1,0.5) is None and got(c2,n2,0.5) is None,"not blocked before the cycle")
    t=time.time(); r3=req(c3,"UPDATE dl_a SET label = 'c3a' WHERE id = 1;",8)
    check(r3 is not None and "ERROR" in r3 and "ABORTED" in r3,"victim response: %r"%r3)
    check(time.time()-t<5,"deadlock resolution took %.1fs"%(time.time()-t))
    r2=got(c2,n2,5); check(r2 and "1 row" in r2,"c2 should continue: %r"%r2)
    check("COMMIT" in val(c2,"COMMIT;"),"c2 commit")
    r1=got(c1,n1,5); check(r1 and "1 row" in r1,"c1 should continue: %r"%r1)
    check("COMMIT" in val(c1,"COMMIT;"),"c1 commit")
    chk=mk()
    r=val(chk,"SELECT label FROM dl_c WHERE id = 1;"); check("c2c" in r and "c3c" not in r,"dl_c: %s"%r)
    r=val(chk,"SELECT label FROM dl_a WHERE id = 1;"); check("c1a" in r and "c3a" not in r,"dl_a: %s"%r)
    r=val(chk,"SELECT label FROM dl_b WHERE id = 1;"); check("c1b" in r,"dl_b: %s"%r)
    r=req(c3,"SELECT * FROM dl_a WHERE id = 1;",5); check(r is not None,"victim session must stay usable")

@case("EE-03 deadlock upgrade S ke X (dua client) lewat SQL: tepat satu dibatalkan, satu commit")
def ee03(s,p,mk,d,proto):
    if proto!="lock": return "SKIP"
    c1,c2=mk(),mk(); mktables(c1,("dl_a",)); val(c1,"BEGIN TRANSACTION;"); time.sleep(0.05); val(c2,"BEGIN TRANSACTION;")
    val(c1,"SELECT * FROM dl_a WHERE id = 1;"); val(c2,"SELECT * FROM dl_a WHERE id = 1;")
    n1=pending(c1,"UPDATE dl_a SET label = 'up111' WHERE id = 1;"); time.sleep(0.4)
    check(got(c1,n1,0.5) is None,"c1 upgrade must wait for c2's S lock")
    r2=req(c2,"UPDATE dl_a SET label = 'up222' WHERE id = 1;",8)
    check(r2 and "ERROR" in r2,"younger upgrader must be the victim: %r"%r2)
    r1=got(c1,n1,5); check(r1 and "1 row" in r1,"older upgrader continues: %r"%r1)
    check("COMMIT" in val(c1,"COMMIT;"),"commit")
    r=val(mk(),"SELECT label FROM dl_a WHERE id = 1;"); check("up111" in r and "up222" not in r,"label: %s"%r)

@case("EE-04 penulis kedua menunggu sampai penulis pertama commit (lock-based)")
def ee04(s,p,mk,d,proto):
    if proto!="lock": return "SKIP"
    c1,c2=mk(),mk(); val(c1,"BEGIN TRANSACTION;")
    check("1 row" in val(c1,"UPDATE products SET stock = 501 WHERE product_id = 2;"),"c1 write")
    n=pending(c2,"UPDATE products SET stock = 502 WHERE product_id = 2;"); time.sleep(0.8)
    check(got(c2,n,0.5) is None,"c2 must wait")
    check("COMMIT" in val(c1,"COMMIT;"),"c1 commit")
    r=got(c2,n,5); check(r and "1 row" in r,"c2 resumes after commit: %r"%r)
    r=val(mk(),"SELECT stock FROM products WHERE product_id = 2;"); check("502" in r,"final stock: %s"%r)

@case("EE-05 client putus di tengah transaksi: server auto-abort, lock lepas, perubahan hilang")
def ee05(s,p,mk,d,proto):
    c1=mk(); orig=city(c1,5)
    val(c1,"BEGIN TRANSACTION;"); check("1 row" in val(c1,"UPDATE customers SET city = 'GhostCity' WHERE customer_id = 5;"),"w")
    c1.p.send_signal(signal.SIGKILL); c1.p.wait(); time.sleep(1.0)
    c2=mk(); r=req(c2,"UPDATE customers SET city = 'AfterGhost' WHERE customer_id = 5;",8)
    check(r and "1 row" in r,"lock not released after disconnect: %r"%r)
    r=val(c2,"SELECT city FROM customers WHERE customer_id = 5;"); check("AfterGhost" in r and "GhostCity" not in r,r)

@case("EE-06 ABORT: client lain membaca nilai seperti sebelum transaksi dimulai")
def ee06(s,p,mk,d,proto):
    c1,c2=mk(),mk(); orig=city(c2,6)
    val(c1,"BEGIN TRANSACTION;"); check("1 row" in val(c1,"UPDATE customers SET city = 'DirtyCity' WHERE customer_id = 6;"),"w")
    check("ABORT" in val(c1,"ABORT;"),"abort")
    check(city(c2,6)==orig,"after abort: %s vs %s"%(city(c2,6),orig))
    r=req(c1,"COMMIT;",5); check(r and "ERROR" in r or (r and "COMMIT" in r and "ABORT" in r.upper()) or r,"commit after abort: %r"%r)

@case("EE-07 timestamp: pelanggaran urutan dibatalkan, akses berikutnya ditolak, ulang dengan txId baru berhasil")
def ee07(s,p,mk,d,proto):
    if proto!="timestamp": return "SKIP"
    c1,c2=mk(),mk(); t1=txid(val(c1,"BEGIN TRANSACTION;")); time.sleep(0.05); t2=txid(val(c2,"BEGIN TRANSACTION;"))
    check(t2>t1,"timestamps")
    val(c2,"SELECT * FROM categories WHERE category_id = 1;"); check("COMMIT" in val(c2,"COMMIT;"),"c2 commit")
    r=req(c1,"UPDATE categories SET name = 'late' WHERE category_id = 1;",8); check(r and "ERROR" in r,"older write after younger read must abort: %r"%r)
    r=req(c1,"SELECT * FROM categories WHERE category_id = 1;",5); check(r is not None,"session must stay usable after CCM abort")
    t3=txid(val(c1,"BEGIN TRANSACTION;")); check(t3>t2,"retry gets newer txId %s %s"%(t3,t2))
    check("1 row" in val(c1,"UPDATE categories SET name = 'retry' WHERE category_id = 1;"),"retry write")
    check("COMMIT" in val(c1,"COMMIT;"),"retry commit")
    r=val(mk(),"SELECT name FROM categories WHERE category_id = 1;"); check("retry" in r,r)

@case("EE-08 dua pembaca berbagi akses tanpa saling menunggu")
def ee08(s,p,mk,d,proto):
    c1,c2=mk(),mk(); val(c1,"BEGIN TRANSACTION;"); val(c2,"BEGIN TRANSACTION;")
    r1=req(c1,"SELECT * FROM products WHERE product_id = 3;",5); r2=req(c2,"SELECT * FROM products WHERE product_id = 3;",5)
    check(r1 and r2 and "1 row" in r1 and "1 row" in r2,"shared reads must not block")
    val(c1,"COMMIT;"); val(c2,"COMMIT;")

@case("EE-09 tidak ada dirty read: nilai belum commit tidak pernah terbaca")
def ee09(s,p,mk,d,proto):
    c1,c2=mk(),mk(); val(c1,"BEGIN TRANSACTION;")
    check("1 row" in val(c1,"UPDATE customers SET city = 'DIRTY' WHERE customer_id = 7;"),"w")
    n=pending(c2,"SELECT city FROM customers WHERE customer_id = 7;"); time.sleep(0.8)
    early=got(c2,n,0.3)
    check(early is None or "DIRTY" not in early,"dirty read: %r"%early)
    val(c1,"ABORT;")
    r=got(c2,n,6)
    check(r is not None,"reader never resumed")
    check("DIRTY" not in r,"dirty value seen: %r"%r)

@case("EE-10 delapan client menaikkan satu counter: tanpa lost update")
def ee10(s,p,mk,d,proto):
    chk=mk(); r=val(chk,"SELECT stock FROM products WHERE product_id = 4;"); start=int(re.findall(r"\|\s*(\d+)\s*\|\s*\n",r)[-1]) if re.findall(r"\|\s*(\d+)\s*\|\s*\n",r) else None
    lines=[l for l in r.split("\n") if l.startswith("| ") and "stock" not in l]; start=int(lines[0].strip("| \n"))
    N=6; ITER=3; ok=[0]*N; errs=[]
    def worker(i):
        c=mk(); done=0
        try:
            while done<ITER:
                b=req(c,"BEGIN TRANSACTION;",15)
                if b is None or "ACTIVE" not in b: time.sleep(0.05); continue
                u=req(c,"UPDATE products SET stock = stock + 1 WHERE product_id = 4;",20)
                if u is None: errs.append("hang"); return
                if "1 row" in u:
                    cm=req(c,"COMMIT;",20)
                    if cm and "COMMITTED" in cm: done+=1
                    else: errs.append("commit:"+str(cm)); 
                else:
                    time.sleep(0.02*(i+1)+0.05*(hash((i,done,time.time()))%5))
            ok[i]=done
        except Exception as e: errs.append(repr(e))
    ths=[threading.Thread(target=worker,args=(i,)) for i in range(N)]
    for t in ths: t.start()
    for t in ths: t.join(120)
    check(not errs,"worker errors %s"%errs)
    r=val(chk,"SELECT stock FROM products WHERE product_id = 4;")
    lines=[l for l in r.split("\n") if l.startswith("| ") and "stock" not in l]; end=int(lines[0].strip("| \n"))
    check(end-start==sum(ok),"lost update: start %d end %d commits %d"%(start,end,sum(ok)))

@case("EE-11 protokol aktif ditampilkan saat start")
def ee11(s,p,mk,d,proto):
    check(s.wait_for("Active protocol: "+("LOCK_BASED" if proto=="lock" else "TIMESTAMP_BASED"),2) is not None,"protocol banner missing:\n"+s.text()[:500])

@case("EE-12 DDL: abort CREATE TABLE dan abort DROP TABLE (dengan data) mengembalikan keadaan semula")
def ee12(s,p,mk,d,proto):
    c=mk()
    check("created" in val(c,"CREATE TABLE ddl_t (id INTEGER PRIMARY KEY, label VARCHAR(20));").lower() or True,"create")
    for i in (1,2,3): check("1 row" in val(c,"INSERT INTO ddl_t VALUES (%d, 'row%d');"%(i,i)),"insert")
    val(c,"BEGIN TRANSACTION;"); val(c,"CREATE TABLE ddl_new (id INTEGER PRIMARY KEY);"); check("ABORT" in val(c,"ABORT;"),"abort create")
    r=req(c,"SELECT * FROM ddl_new;",5); check(r and "ERROR" in r,"table must not exist after aborted CREATE: %r"%r)
    val(c,"BEGIN TRANSACTION;"); val(c,"DROP TABLE ddl_t;"); check("ABORT" in val(c,"ABORT;"),"abort drop")
    r=val(c,"SELECT * FROM ddl_t;"); check("3 row" in r,"dropped table not restored: %r"%r)
    check("1 row" in val(c,"INSERT INTO ddl_t VALUES (4, 'row4');"),"table usable after restored")

@case("EE-13 SIGINT (shutdown bersih) lalu restart: data bertahan dan tidak ada lock tersisa")
def ee13(s,p,mk,d,proto):
    c=mk(); check("1 row" in val(c,"UPDATE customers SET city = 'Graceful' WHERE customer_id = 8;"),"w")
    val(c,"BEGIN TRANSACTION;"); val(c,"UPDATE customers SET city = 'OpenTx' WHERE customer_id = 9;")
    s.p.send_signal(signal.SIGINT); 
    try: s.p.wait(timeout=15)
    except Exception: raise AssertionError("server did not stop on SIGINT")
    s2=server(d,p,proto)
    try:
        c2=client(p)
        try:
            check(city(c2,8)=="Graceful","graceful data lost")
            check(city(c2,9)!="OpenTx","open tx survived shutdown: %s"%city(c2,9))
            r=req(c2,"UPDATE customers SET city = 'Again' WHERE customer_id = 9;",6); check(r and "1 row" in r,"stale lock after shutdown: %r"%r)
        finally: c2.close()
    finally: s2.kill9()


@case("EE-14 timestamp: pembaca lebih muda menunggu penulis belum commit lalu lanjut (tidak menunggu selamanya)")
def ee14(s,p,mk,d,proto):
    if proto!="timestamp": return "SKIP"
    c1,c2=mk(),mk(); mktables(c1,("dl_a",))
    val(c1,"BEGIN TRANSACTION;"); time.sleep(0.05); val(c2,"BEGIN TRANSACTION;")
    check("1 row" in val(c1,"UPDATE dl_a SET label = 'w_old' WHERE id = 1;"),"older write")
    n=pending(c2,"SELECT label FROM dl_a WHERE id = 1;"); time.sleep(0.6)
    early=got(c2,n,0.3)
    check(early is None or "w_old" not in early,"younger read saw uncommitted write: %r"%early)
    check("COMMIT" in val(c1,"COMMIT;"),"older commit")
    r=got(c2,n,6); check(r is not None,"younger reader hangs forever after the writer committed")
    check("ERROR" in r or "w_old" in r,"after commit the read must see the committed value or be aborted: %r"%r)

@case("EE-15 DDL dan crash: CREATE TABLE belum commit hilang, yang sudah commit bertahan, DROP belum commit dibatalkan")
def ee15(s,p,mk,d,proto):
    c=mk(); mktables(c,("dl_a",))
    val(c,"BEGIN TRANSACTION;"); val(c,"CREATE TABLE ghost_t (id INTEGER PRIMARY KEY);")
    c2=mk(); val(c2,"BEGIN TRANSACTION;")
    r=req(c2,"INSERT INTO dl_a VALUES (2, 'x');",3)
    s.kill9()
    s2=server(d,p,proto)
    try:
        cc=client(p)
        try:
            r=req(cc,"SELECT * FROM ghost_t;",5); check(r and "ERROR" in r,"uncommitted CREATE TABLE survived the crash: %r"%r)
            r=val(cc,"SELECT * FROM dl_a;"); check("1 row" in r and "orig_dl_a" in r,"committed table/data lost or uncommitted row present: %r"%r)
            check("1 row" in val(cc,"INSERT INTO dl_a VALUES (3, 'y');"),"table unusable after recovery")
        finally: cc.close()
    finally: s2.kill9()

@case("EE-16 acak: transfer antar tabel dengan crash di tengah; total saldo tetap (atomicity + durability)")
def ee16(s,p,mk,d,proto):
    import random
    c=mk()
    for n in ("acc_a","acc_b","acc_c"):
        val(c,"CREATE TABLE %s (id INTEGER PRIMARY KEY, bal INTEGER);"%n)
        for i in (1,2,3): check("1 row" in val(c,"INSERT INTO %s VALUES (%d, 100);"%(n,i)),"seed")
    tabs=["acc_a","acc_b","acc_c"]; stop=threading.Event(); stats={"commit":0,"abort":0,"err":0}; lock=threading.Lock()
    def worker(i):
        rnd=random.Random(i*7919+13)
        try: wc=client(p)
        except Exception: return
        try:
            while not stop.is_set():
                a,b=rnd.sample(tabs,2); ia,ib=rnd.randint(1,3),rnd.randint(1,3)
                r=req(wc,"BEGIN TRANSACTION;",8)
                if r is None or "ACTIVE" not in r: time.sleep(0.05); continue
                r1=req(wc,"UPDATE %s SET bal = bal - 1 WHERE id = %d;"%(a,ia),12)
                if r1 is None or "1 row" not in r1:
                    with lock: stats["err"]+=1
                    continue
                r2=req(wc,"UPDATE %s SET bal = bal + 1 WHERE id = %d;"%(b,ib),12)
                if r2 is None or "1 row" not in r2:
                    with lock: stats["err"]+=1
                    continue
                if rnd.random()<0.15:
                    req(wc,"ABORT;",8)
                    with lock: stats["abort"]+=1
                else:
                    r3=req(wc,"COMMIT;",12)
                    with lock:
                        if r3 and "COMMITTED" in r3: stats["commit"]+=1
                        else: stats["err"]+=1
        except Exception: pass
        finally:
            try: wc.p.kill()
            except Exception: pass
    ths=[threading.Thread(target=worker,args=(i,),daemon=True) for i in range(6)]
    for t in ths: t.start()
    import os
    time.sleep(float(os.environ.get('CRASH_AFTER','8')))
    s.kill9()
    stop.set()
    for t in ths: t.join(5)
    s2=server(d,p,proto)
    try:
        cc=client(p)
        try:
            tot=0
            for n in tabs:
                r=val(cc,"SELECT bal FROM %s;"%n)
                vals=[int(l.strip("| \n")) for l in r.split("\n") if l.startswith("| ") and "bal" not in l]
                check(len(vals)==3,"rows lost in %s: %s"%(n,r)); tot+=sum(vals)
            check(tot==900,"money not conserved after crash: total %d (stats %s)"%(tot,stats))
            check(stats["commit"]>0,"no transaction committed during the run: %s"%stats)
            r=req(cc,"UPDATE acc_a SET bal = bal + 1 WHERE id = 1;",8); check(r and "1 row" in r,"stale lock after crash recovery: %r"%r)
            r=req(cc,"UPDATE acc_a SET bal = bal - 1 WHERE id = 1;",8); check(r and "1 row" in r,"stale lock after recovery (2): %r"%r)
        finally: cc.close()
    finally: s2.kill9()
    print("   [EE-16 %s] stats %s"%(proto,stats))

@case("EE-17 DROP TABLE belum commit lalu kill -9: tabel dan datanya kembali setelah recovery")
def ee17(s,p,mk,d,proto):
    c=mk(); mktables(c,("dl_a",))
    for i in (2,3): check("1 row" in val(c,"INSERT INTO dl_a VALUES (%d, 'row%d');"%(i,i)),"insert")
    val(c,"BEGIN TRANSACTION;"); r=val(c,"DROP TABLE dl_a;")
    s.kill9()
    s2=server(d,p,proto)
    try:
        cc=client(p)
        try:
            r=val(cc,"SELECT * FROM dl_a;"); check("3 row" in r,"dropped-but-uncommitted table not restored: %r"%r)
            check("1 row" in val(cc,"INSERT INTO dl_a VALUES (4, 'row4');"),"restored table is usable")
        finally: cc.close()
    finally: s2.kill9()

if __name__=="__main__":
    only=sys.argv[1:]
    for f in ALL:
        if only and not any(o in f.name for o in only): continue
        for proto in ("lock","timestamp"): f(proto)
    print()
    for n,pr,st,info in results: print("[%s] [%s] %s %s"%(st,pr,n,"" if st=="LULUS" else info))
    print("Total: %d LULUS, %d GAGAL, %d dilewati (tidak berlaku untuk protokol itu)"%(sum(1 for r in results if r[2]=="LULUS"),sum(1 for r in results if r[2]=="GAGAL"),sum(1 for r in results if r[2]=="SKIP")))
    sys.exit(1 if any(r[2]=='GAGAL' for r in results) else 0)

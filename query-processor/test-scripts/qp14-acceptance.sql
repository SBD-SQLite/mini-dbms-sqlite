-- QP-14 acceptance script for the SQLike Query Processor (run by sqlike.qp.QP1ScriptDriver).
-- Starts from the sample database:
--   student(id, name, gpa):    (1, Ani, 3.5) (2, Budi, 3.2) (3, Cici, 3.8)
--   enrollment(student_id, course): (1, IF3140) (2, IF3140) (3, IF3130)
-- Scenarios run in order and share the database.

-- scenario: QP-05 scan returns every row with its header
SELECT * FROM student;
-- expect: columns id, name, gpa
-- expect-row: 1 | Ani | 3.5
-- expect-row: 2 | Budi | 3.2
-- expect-row: 3 | Cici | 3.8

-- scenario: QP-05 projection keeps the requested column order
SELECT name, id FROM student;
-- expect: columns name, id
-- expect-row: Ani | 1
-- expect-row: Budi | 2
-- expect-row: Cici | 3

-- scenario: QP-06 filter with comparison and AND
SELECT * FROM student WHERE gpa > 3.4;
-- expect-row: 1 | Ani | 3.5
-- expect-row: 3 | Cici | 3.8
SELECT id FROM student WHERE gpa > 3.0 AND id < 3;
-- expect-row: 1
-- expect-row: 2
SELECT * FROM student WHERE name = 'Zara';
-- expect: rows 0
-- expect: columns id, name, gpa

-- scenario: QP-09 sort ascending by default and descending
SELECT name FROM student ORDER BY gpa DESC;
-- expect-row: Cici
-- expect-row: Ani
-- expect-row: Budi
SELECT name, gpa FROM student ORDER BY name;
-- expect-row: Ani | 3.5
-- expect-row: Budi | 3.2
-- expect-row: Cici | 3.8

-- scenario: QP-08 join with ON condition, filter, and sort
SELECT s.name, e.course FROM student s JOIN enrollment e ON s.id = e.student_id ORDER BY s.name;
-- expect: columns name, course
-- expect-row: Ani | IF3140
-- expect-row: Budi | IF3140
-- expect-row: Cici | IF3130
SELECT s.name FROM student s JOIN enrollment e ON s.id = e.student_id WHERE e.course = 'IF3140' ORDER BY s.name DESC;
-- expect-row: Budi
-- expect-row: Ani

-- scenario: QP-10 insert is visible to later reads
INSERT INTO student VALUES (4, 'Dodi', 3.0);
-- expect: affected 1
SELECT * FROM student WHERE id = 4;
-- expect-row: 4 | Dodi | 3.0

-- scenario: QP-11 update one column, including an expression
UPDATE student SET gpa = 3.6 WHERE id = 4;
-- expect: affected 1
UPDATE student SET gpa = gpa * 2 WHERE id = 4;
-- expect: affected 1
SELECT gpa FROM student WHERE id = 4;
-- expect-row: 7.2

-- scenario: QP-07 delete with and without matching rows
DELETE FROM student WHERE id = 99;
-- expect: affected 0
DELETE FROM student WHERE id = 4;
-- expect: affected 1
SELECT * FROM student WHERE id = 4;
-- expect: rows 0

-- scenario: QP-12 explicit transaction commits its changes
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
INSERT INTO student VALUES (5, 'Eka', 3.1);
-- expect: affected 1
COMMIT;
-- expect: transaction COMMITTED
SELECT name FROM student WHERE id = 5;
-- expect-row: Eka

-- scenario: QP-12 ABORT undoes the transaction's changes
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
DELETE FROM student WHERE id = 5;
-- expect: affected 1
UPDATE student SET gpa = 1.0 WHERE id = 1;
-- expect: affected 1
ABORT;
-- expect: transaction ABORTED
SELECT gpa FROM student WHERE id = 1;
-- expect-row: 3.5
SELECT name FROM student WHERE id = 5;
-- expect-row: Eka

-- scenario: QP-12 invalid transaction commands are rejected clearly
COMMIT;
-- expect: error INVALID_STATE
ABORT;
-- expect: error INVALID_STATE
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
BEGIN TRANSACTION;
-- expect: error INVALID_STATE
ABORT;
-- expect: transaction ABORTED

-- scenario: QP-01 errors are reported and the session stays usable
SELEC * FORM student;
-- expect: error QO SYNTAX_ERROR
SELECT * FROM missing;
-- expect: error QO SEMANTIC_ERROR
SELECT nope FROM student;
-- expect: error QO SEMANTIC_ERROR
SELECT name FROM student WHERE id = 2;
-- expect-row: Budi

-- scenario: QP-12 error before execution keeps the explicit transaction
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
INSERT INTO student VALUES (6, 'Fani', 3.3);
-- expect: affected 1
SELEC broken;
-- expect: error QO SYNTAX_ERROR
COMMIT;
-- expect: transaction COMMITTED
SELECT name FROM student WHERE id = 6;
-- expect-row: Fani

-- scenario: QP-02 sessions are independent and a disconnect aborts the open transaction
-- session: A
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
INSERT INTO student VALUES (7, 'Gilang', 2.9);
-- expect: affected 1
-- session: B
BEGIN TRANSACTION;
-- expect: transaction ACTIVE
ABORT;
-- expect: transaction ABORTED
-- close-session: A
-- session: B
SELECT * FROM student WHERE id = 7;
-- expect: rows 0

-- scenario: QP-13 create index, then read through it
CREATE INDEX idx_student_id ON student(id);
-- expect: message created
SELECT name FROM student WHERE id = 3;
-- expect-row: Cici
BEGIN TRANSACTION;
CREATE INDEX idx_student_gpa ON student(gpa);
-- expect: error INVALID_STATE
ABORT;
-- expect: transaction ABORTED

-- scenario: QP-16 LIMIT after sort (bonus)
SELECT name FROM student ORDER BY name LIMIT 2;
-- expect-row: Ani
-- expect-row: Budi

-- scenario: QP-16 AS table alias and subquery in WHERE (bonus)
SELECT s.name FROM student AS s WHERE s.gpa > (SELECT gpa FROM student WHERE id = 1) ORDER BY s.name ASC;
-- expect-row: Cici
SELECT s.name FROM student AS s WHERE EXISTS (SELECT e.course FROM enrollment AS e WHERE e.student_id = s.id AND e.course = 'IF3130');
-- expect-row: Cici

-- scenario: QP-15 CREATE TABLE and DROP TABLE RESTRICT vs CASCADE (bonus)
CREATE TABLE dept (id INTEGER PRIMARY KEY, title VARCHAR(20));
-- expect: message created
CREATE TABLE club (id INTEGER PRIMARY KEY, dept_id INTEGER, FOREIGN KEY (dept_id) REFERENCES dept(id));
-- expect: message created
INSERT INTO dept VALUES (10, 'Informatics');
-- expect: affected 1
INSERT INTO club VALUES (100, 10);
-- expect: affected 1
DROP TABLE dept RESTRICT;
-- expect: error SEMANTIC_ERROR
DROP TABLE dept CASCADE;
-- expect: message dropped
SELECT id, dept_id FROM club;
-- expect-row: 100 | 10
DROP TABLE club;
-- expect: message dropped


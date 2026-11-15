import sys
from collections import deque
LEVELS = [
"""#####
#@$.#
#####""",
"""######
#.   #
#@$$ #
#.   #
######""",
"""#######
#  .  #
# #$# #
#@ $ .#
#  #  #
#######""",
"""  ####
###  #
#.$  #
#.$@ #
###  #
  ####""",
"""########
#  .   #
# $##$ #
#  @   #
# $##$ #
#  .  .#
###   .#
  ######""",
"""#######
#.  ..#
# $$  #
##@ $ #
 # $  #
 # .###
 ####""",
"""  #####
###   #
#. $# #
#.$@  #
#. $ ##
######""",
"""#########
#   #   #
# $ . $ #
#.##@##.#
# $ . $ #
#   #   #
#########""",
]
def parse(s):
    g=s.split('\n'); walls=set(); goals=set(); boxes=set(); p=None
    for y,row in enumerate(g):
        for x,c in enumerate(row):
            if c=='#': walls.add((x,y))
            if c in '.+*': goals.add((x,y))
            if c in '$*': boxes.add((x,y))
            if c in '@+': p=(x,y)
    return walls,goals,frozenset(boxes),p
def solve(s, limit=3000000):
    walls,goals,boxes,p=parse(s)
    assert len(goals)==len(boxes), (len(goals),len(boxes))
    start=(p,boxes); q=deque([(start,0)]); seen={start}
    while q:
        (p,b),d=q.popleft()
        if b==goals: return d, len(seen)
        for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
            n=(p[0]+dx,p[1]+dy)
            if n in walls: continue
            nb=b
            if n in b:
                m=(n[0]+dx,n[1]+dy)
                if m in walls or m in b: continue
                nb=(b-{n})|{m}
            st=(n,nb)
            if st not in seen:
                seen.add(st); q.append((st,d+1))
                if len(seen)>limit: return None, len(seen)
    return None, len(seen)
for i,l in enumerate(LEVELS):
    print(i+1, solve(l))

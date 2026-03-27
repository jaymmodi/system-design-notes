# Redis Commands — Cheat Sheet

---

## Keys (universal)

| Command | Example | What it does |
|---------|---------|--------------|
| `DEL key` | `DEL user:1` | Delete key |
| `EXISTS key` | `EXISTS user:1` | 1 if exists, 0 if not |
| `EXPIRE key sec` | `EXPIRE session:x 3600` | Set TTL in seconds |
| `PEXPIRE key ms` | `PEXPIRE lock:x 500` | Set TTL in milliseconds |
| `TTL key` | `TTL session:x` | Remaining TTL (-1 = no expiry, -2 = gone) |
| `PERSIST key` | `PERSIST session:x` | Remove TTL, make permanent |
| `RENAME key newkey` | `RENAME tmp final` | Rename key |
| `TYPE key` | `TYPE user:1` | Returns: string/hash/list/set/zset/stream |
| `SCAN cursor MATCH pattern COUNT n` | `SCAN 0 MATCH user:* COUNT 100` | Iterate keys safely (never use KEYS * in prod) |

---

## Strings

| Command | Example | What it does |
|---------|---------|--------------|
| `SET key value` | `SET name "jay"` | Set value |
| `SET key value EX sec` | `SET token "abc" EX 3600` | Set with TTL |
| `SET key value NX` | `SET lock:x "1" NX` | Set only if NOT exists (atomic) |
| `SET key value XX` | `SET name "jay" XX` | Set only if EXISTS |
| `SET key value GET` | `SET name "new" GET` | Set and return OLD value (atomic) |
| `GET key` | `GET name` | Get value |
| `GETDEL key` | `GETDEL token` | Get and delete atomically |
| `MSET k1 v1 k2 v2` | `MSET a 1 b 2` | Set multiple keys |
| `MGET k1 k2` | `MGET a b` | Get multiple keys |
| `INCR key` | `INCR counter` | Increment integer by 1 |
| `INCRBY key n` | `INCRBY counter 5` | Increment by n |
| `DECR key` | `DECR counter` | Decrement by 1 |
| `APPEND key value` | `APPEND log "entry"` | Append to string |
| `STRLEN key` | `STRLEN name` | Length of string value |
| `SETNX key value` | `SETNX lock "1"` | Deprecated — use SET NX instead |

---

## Hashes (object / map)

| Command | Example | What it does |
|---------|---------|--------------|
| `HSET key field value` | `HSET user:1 name "jay"` | Set one field |
| `HSET key f1 v1 f2 v2` | `HSET user:1 name "jay" age 30` | Set multiple fields |
| `HGET key field` | `HGET user:1 name` | Get one field |
| `HMGET key f1 f2` | `HMGET user:1 name age` | Get multiple fields |
| `HGETALL key` | `HGETALL user:1` | Get all fields and values |
| `HDEL key field` | `HDEL user:1 age` | Delete a field |
| `HEXISTS key field` | `HEXISTS user:1 name` | 1 if field exists |
| `HLEN key` | `HLEN user:1` | Number of fields |
| `HKEYS key` | `HKEYS user:1` | All field names |
| `HVALS key` | `HVALS user:1` | All values |
| `HINCRBY key field n` | `HINCRBY user:1 age 1` | Increment integer field |
| `HSETNX key field value` | `HSETNX user:1 name "jay"` | Set field only if not exists |

---

## Lists (ordered, duplicates allowed)

| Command | Example | What it does |
|---------|---------|--------------|
| `LPUSH key val` | `LPUSH queue "task1"` | Push to HEAD (left) |
| `RPUSH key val` | `RPUSH queue "task1"` | Push to TAIL (right) |
| `LPOP key` | `LPOP queue` | Pop from HEAD |
| `RPOP key` | `RPOP queue` | Pop from TAIL |
| `BLPOP key sec` | `BLPOP queue 5` | Blocking pop from HEAD (waits up to 5s) |
| `BRPOP key sec` | `BRPOP queue 5` | Blocking pop from TAIL |
| `LRANGE key start stop` | `LRANGE queue 0 -1` | Get elements (0,-1 = all) |
| `LLEN key` | `LLEN queue` | Length of list |
| `LINDEX key i` | `LINDEX queue 0` | Get element at index |
| `LSET key i val` | `LSET queue 0 "new"` | Set element at index |
| `LREM key count val` | `LREM queue 1 "task1"` | Remove N occurrences of value |
| `LTRIM key start stop` | `LTRIM log 0 999` | Keep only elements in range |
| `LINSERT key BEFORE\|AFTER pivot val` | `LINSERT q BEFORE "b" "a"` | Insert before/after element |

**Patterns**: LPUSH + RPOP = queue. LPUSH + LPOP = stack. BRPOP = reliable blocking consumer.

---

## Sets (unordered, unique)

| Command | Example | What it does |
|---------|---------|--------------|
| `SADD key member` | `SADD online "user:1"` | Add member(s) |
| `SREM key member` | `SREM online "user:1"` | Remove member |
| `SISMEMBER key member` | `SISMEMBER online "user:1"` | 1 if member exists |
| `SMISMEMBER key m1 m2` | `SMISMEMBER online "u:1" "u:2"` | Check multiple members |
| `SMEMBERS key` | `SMEMBERS online` | All members (careful at scale) |
| `SCARD key` | `SCARD online` | Count of members |
| `SPOP key` | `SPOP online` | Remove and return random member |
| `SRANDMEMBER key n` | `SRANDMEMBER online 3` | Get n random members (no remove) |
| `SUNION k1 k2` | `SUNION set1 set2` | Union of sets |
| `SINTER k1 k2` | `SINTER set1 set2` | Intersection |
| `SDIFF k1 k2` | `SDIFF set1 set2` | Difference (in k1, not k2) |
| `SUNIONSTORE dest k1 k2` | `SUNIONSTORE result s1 s2` | Store union result |
| `SINTERSTORE dest k1 k2` | `SINTERSTORE result s1 s2` | Store intersection result |

---

## Sorted Sets / ZSETs (unique members + score)

| Command | Example | What it does |
|---------|---------|--------------|
| `ZADD key score member` | `ZADD leaderboard 1500 "jay"` | Add with score |
| `ZADD key NX score member` | `ZADD lb NX 1500 "jay"` | Add only if not exists |
| `ZADD key XX score member` | `ZADD lb XX 1600 "jay"` | Update only if exists |
| `ZSCORE key member` | `ZSCORE leaderboard "jay"` | Get score |
| `ZINCRBY key n member` | `ZINCRBY leaderboard 100 "jay"` | Increment score |
| `ZRANK key member` | `ZRANK leaderboard "jay"` | Rank (0 = lowest score) |
| `ZREVRANK key member` | `ZREVRANK leaderboard "jay"` | Rank (0 = highest score) |
| `ZRANGE key start stop` | `ZRANGE leaderboard 0 9` | Members by rank (low→high) |
| `ZRANGE key start stop REV` | `ZRANGE leaderboard 0 9 REV` | Members high→low (Redis 6.2+) |
| `ZREVRANGE key start stop` | `ZREVRANGE leaderboard 0 9` | Members high→low (older) |
| `ZRANGEBYSCORE key min max` | `ZRANGEBYSCORE lb 1000 2000` | Members in score range |
| `ZRANGEBYLEX key min max` | `ZRANGEBYLEX names [a [z` | Members in lex range (equal scores) |
| `ZREM key member` | `ZREM leaderboard "jay"` | Remove member |
| `ZREMRANGEBYSCORE key min max` | `ZREMRANGEBYSCORE lb 0 100` | Remove by score range |
| `ZCARD key` | `ZCARD leaderboard` | Count of members |
| `ZCOUNT key min max` | `ZCOUNT leaderboard 1000 2000` | Count in score range |
| `ZPOPMIN key n` | `ZPOPMIN leaderboard 3` | Pop n lowest score members |
| `ZPOPMAX key n` | `ZPOPMAX leaderboard 3` | Pop n highest score members |
| `ZUNIONSTORE dest n k1 k2` | `ZUNIONSTORE out 2 z1 z2` | Store union (sum scores) |
| `ZINTERSTORE dest n k1 k2` | `ZINTERSTORE out 2 z1 z2` | Store intersection |

**Internally**: skiplist + hashtable. O(log n) add/remove/rank, O(1) score lookup.

---

## Bitmaps (bit-level ops on strings)

| Command | Example | What it does |
|---------|---------|--------------|
| `SETBIT key pos 0\|1` | `SETBIT visits 42 1` | Set bit at position |
| `GETBIT key pos` | `GETBIT visits 42` | Get bit at position (0 or 1) |
| `BITCOUNT key` | `BITCOUNT visits` | Count of set bits |
| `BITCOUNT key start end` | `BITCOUNT visits 0 3` | Count in byte range |
| `BITPOS key 0\|1` | `BITPOS visits 0` | First position of 0 or 1 |
| `BITOP AND dest k1 k2` | `BITOP AND out bm1 bm2` | Bitwise AND → dest |
| `BITOP OR dest k1 k2` | `BITOP OR out bm1 bm2` | Bitwise OR → dest |
| `BITOP XOR dest k1 k2` | `BITOP XOR out bm1 bm2` | Bitwise XOR → dest |
| `BITOP NOT dest key` | `BITOP NOT out bm1` | Bitwise NOT → dest |

**Use case**: user activity tracking (1 bit per user ID), bloom filter internals.

---

## HyperLogLog (distinct count)

| Command | Example | What it does |
|---------|---------|--------------|
| `PFADD key element` | `PFADD visitors "user:1"` | Add element |
| `PFADD key e1 e2 e3` | `PFADD visitors "u:1" "u:2"` | Add multiple |
| `PFCOUNT key` | `PFCOUNT visitors` | Estimated distinct count (~0.81% error) |
| `PFCOUNT k1 k2` | `PFCOUNT vis:mon vis:tue` | Distinct count across multiple HLLs |
| `PFMERGE dest k1 k2` | `PFMERGE week mon tue wed` | Merge HLLs → dest (takes max per register) |

**Memory**: always ~12KB regardless of 1M or 1B elements.

---

## Streams (persistent log, consumer groups)

| Command | Example | What it does |
|---------|---------|--------------|
| `XADD key * f v` | `XADD events * type "click"` | Append entry, auto-generate ID |
| `XADD key MAXLEN ~ n * f v` | `XADD events MAXLEN ~ 10000 * ...` | Append + trim to ~n entries |
| `XLEN key` | `XLEN events` | Number of entries |
| `XRANGE key start end` | `XRANGE events - +` | Read entries (- = first, + = last) |
| `XREVRANGE key end start` | `XREVRANGE events + -` | Read in reverse |
| `XREAD COUNT n STREAMS key id` | `XREAD COUNT 10 STREAMS events 0` | Read from ID (0 = beginning) |
| `XREAD BLOCK ms STREAMS key $` | `XREAD BLOCK 5000 STREAMS events $` | Block for new entries ($ = only new) |
| `XGROUP CREATE key grp id` | `XGROUP CREATE events grp1 0` | Create consumer group |
| `XREADGROUP GROUP g c COUNT n STREAMS key >` | `XREADGROUP GROUP g1 c1 COUNT 10 STREAMS events >` | Read undelivered (> = not yet delivered) |
| `XACK key group id` | `XACK events grp1 1234-0` | Acknowledge processed message |
| `XPENDING key group - + n` | `XPENDING events grp1 - + 10` | List unacknowledged messages |
| `XCLAIM key group consumer ms id` | `XCLAIM events grp1 c2 3600000 1234-0` | Steal message idle > ms (dead consumer recovery) |
| `XDEL key id` | `XDEL events 1234-0` | Delete specific entry |
| `XTRIM key MAXLEN ~ n` | `XTRIM events MAXLEN ~ 5000` | Trim stream to n entries |

---

## Pub/Sub (fire-and-forget, no persistence)

| Command | Example | What it does |
|---------|---------|--------------|
| `SUBSCRIBE channel` | `SUBSCRIBE alerts` | Subscribe (blocks, receives messages) |
| `PSUBSCRIBE pattern` | `PSUBSCRIBE alert:*` | Subscribe to pattern |
| `PUBLISH channel message` | `PUBLISH alerts "deploy done"` | Publish to channel |
| `UNSUBSCRIBE channel` | `UNSUBSCRIBE alerts` | Unsubscribe |
| `PUBSUB CHANNELS pattern` | `PUBSUB CHANNELS alert:*` | List active channels |
| `PUBSUB NUMSUB channel` | `PUBSUB NUMSUB alerts` | Subscriber count per channel |

**vs Streams**: Pub/Sub has no persistence — if subscriber is offline, message is lost. Use Streams if you need delivery guarantees.

---

## Transactions

| Command | Example | What it does |
|---------|---------|--------------|
| `MULTI` | `MULTI` | Begin transaction (queue commands) |
| `EXEC` | `EXEC` | Execute all queued commands atomically |
| `DISCARD` | `DISCARD` | Abort transaction, discard queue |
| `WATCH key` | `WATCH balance` | Optimistic lock — EXEC fails if key changed |

```
WATCH balance           # if balance changes before EXEC, abort
MULTI
  DECRBY balance 100
  INCRBY other 100
EXEC                    # returns nil if WATCH key was modified → retry
```

**vs Lua**: MULTI/EXEC batches but doesn't abort on command errors. Lua is truly atomic.

---

## Scripting (Lua — atomic execution)

| Command | Example | What it does |
|---------|---------|--------------|
| `EVAL script numkeys key arg` | `EVAL "return redis.call('GET',KEYS[1])" 1 mykey` | Run Lua script atomically |
| `EVALSHA sha numkeys key arg` | `EVALSHA abc123 1 mykey` | Run cached script by SHA |
| `SCRIPT LOAD script` | `SCRIPT LOAD "return 1"` | Cache script, returns SHA |
| `SCRIPT EXISTS sha` | `SCRIPT EXISTS abc123` | Check if script cached |
| `SCRIPT FLUSH` | `SCRIPT FLUSH` | Clear script cache |

```lua
-- Atomic get-and-set (no built-in for hashes)
local old = redis.call('HGETALL', KEYS[1])
redis.call('HSET', KEYS[1], unpack(ARGV))
return old
```

---

## Server / Ops

| Command | Example | What it does |
|---------|---------|--------------|
| `INFO` | `INFO` | Full server stats |
| `INFO memory` | `INFO memory` | Memory usage breakdown |
| `INFO replication` | `INFO replication` | Master/replica status |
| `DBSIZE` | `DBSIZE` | Number of keys in current DB |
| `FLUSHDB` | `FLUSHDB` | Delete all keys in current DB |
| `FLUSHALL` | `FLUSHALL` | Delete all keys in all DBs |
| `DEBUG SLEEP n` | `DEBUG SLEEP 2` | Block server for n seconds (testing) |
| `OBJECT ENCODING key` | `OBJECT ENCODING user:1` | Internal encoding (listpack, hashtable, etc.) |
| `MEMORY USAGE key` | `MEMORY USAGE user:1` | Bytes used by key |
| `MONITOR` | `MONITOR` | Stream every command received (debug only) |
| `SLOWLOG GET n` | `SLOWLOG GET 10` | Last n slow commands |
| `CONFIG GET param` | `CONFIG GET maxmemory` | Read config |
| `CONFIG SET param val` | `CONFIG SET maxmemory 1gb` | Set config at runtime |
| `SAVE` | `SAVE` | Trigger RDB snapshot (blocking) |
| `BGSAVE` | `BGSAVE` | Trigger RDB snapshot (background) |
| `BGREWRITEAOF` | `BGREWRITEAOF` | Compact AOF file |

---

## Complexity Quick Reference

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| GET / SET / HGET / HSET | O(1) | |
| HGETALL | O(n fields) | |
| SADD / SREM / SISMEMBER | O(1) | |
| SMEMBERS | O(n) | Avoid on large sets |
| SINTER / SUNION | O(n×m) | n=smallest set, m=all sets |
| ZADD / ZREM / ZSCORE | O(log n) | skiplist |
| ZRANK / ZREVRANK | O(log n) | |
| ZRANGE | O(log n + k) | k = returned elements |
| LPUSH / RPUSH / LPOP / RPOP | O(1) | |
| LRANGE | O(n) | |
| SETBIT / GETBIT | O(1) | |
| BITCOUNT | O(n bytes) | |
| PFADD / PFCOUNT | O(1) | |
| PFMERGE | O(n registers) | always 16384 |
| XADD | O(1) amortized | |
| XRANGE | O(n) | |

---

## Common Patterns — One Line Each

```
Distributed lock:      SET lock:x "token" NX EX 30
Rate limiter:          INCR rate:ip:1.2.3.4  +  EXPIRE
Session store:         HSET session:<token> uid 42  +  EXPIRE
Leaderboard:           ZADD lb <score> <user>  →  ZREVRANGE lb 0 9
Pub/Sub fan-out:       PUBLISH channel msg  (fire and forget)
Reliable queue:        LPUSH + BRPOP  (blocking consumer)
Dedup (exact):         SADD seen:<id>  → SISMEMBER
Dedup (approx):        PFADD hll:<day> <id>  → PFCOUNT
Membership test:       SETBIT bloom:<key> <pos> 1  → GETBIT
Activity tracking:     SETBIT active:<date> <userId> 1  → BITCOUNT
```

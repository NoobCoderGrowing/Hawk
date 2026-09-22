import { useEffect, useRef, useState } from 'react'

// 两个 tab 是两种检索工具，不是同一件事的两个视图 —— 所以各自的查询词、示例、说明都不同。
const MODES = [
  {
    id: 'fulltext',
    label: '全文搜索',
    endpoint: '/api/search/fulltext',
    hint: '按词命中。输入能在商品标题里出现的词。',
    placeholder: '老黄冰糖',
  },
  {
    id: 'vector',
    label: '向量搜索',
    endpoint: '/api/search/vector',
    hint: '按语义邻近。可以用标题里没有的说法。',
    placeholder: '给老人买的冰糖',
  },
]

const emptyFor = (modes) =>
  Object.fromEntries(modes.map((m) => [m.id, { query: '', data: null }]))

export default function App() {
  const [mode, setMode] = useState(MODES[0].id)
  const [meta, setMeta] = useState(null)
  const [state, setState] = useState(() => emptyFor(MODES))
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const inputRef = useRef(null)

  const active = MODES.find((m) => m.id === mode)
  const current = state[mode]

  useEffect(() => {
    fetch('/api/meta')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error('后端未就绪'))))
      .then(setMeta)
      .catch((e) => setError(e.message))
  }, [])

  function switchMode(id) {
    setMode(id)
    setError(null)
    inputRef.current?.focus()
  }

  function setQuery(query) {
    setState((s) => ({ ...s, [mode]: { ...s[mode], query } }))
  }

  async function run(e) {
    e?.preventDefault()
    const q = current.query.trim()
    if (!q || loading) return

    setLoading(true)
    setError(null)
    const t0 = performance.now()
    try {
      const r = await fetch(`${active.endpoint}?q=${encodeURIComponent(q)}&topK=20`)
      const body = await r.json()
      if (!r.ok) throw new Error(body.message || `请求失败（${r.status}）`)
      setState((s) => ({ ...s, [mode]: { ...s[mode], data: { ...body, wallMs: Math.round(performance.now() - t0) } } }))
    } catch (err) {
      setError(err.message)
      setState((s) => ({ ...s, [mode]: { ...s[mode], data: null } }))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="page" data-mode={mode}>
      <header className="masthead">
        <h1>Hawk</h1>
        {meta && (
          <p className="masthead-meta">
            倒排 {meta.totalDocs.toLocaleString('en-US')} 篇
            <span className="sep" />
            向量 {meta.indexedVectors.toLocaleString('en-US')} 条
            <span className="sep" />
            {meta.model}
          </p>
        )}
      </header>

      <nav className="tabs" role="tablist">
        {MODES.map((m) => (
          <button
            key={m.id}
            role="tab"
            aria-selected={m.id === mode}
            className="tab"
            onClick={() => switchMode(m.id)}
          >
            {m.label}
          </button>
        ))}
      </nav>

      <p className="hint">{active.hint}</p>

      <form className="querybar" onSubmit={run}>
        <input
          ref={inputRef}
          type="search"
          value={current.query}
          placeholder={active.placeholder}
          aria-label={`${active.label}查询`}
          onChange={(e) => setQuery(e.target.value)}
          autoFocus
        />
        <button type="submit" disabled={loading || !current.query.trim()}>
          {loading ? '检索中' : '搜索'}
        </button>
      </form>

      {error && <p className="notice notice-error">{error}</p>}

      <Results data={current.data} loading={loading} />

      <footer className="footer">
        <p>
          索引与向量都来自 <code>goods.csv</code>，商品 ID 即该文件第一列。
          向量一路由 <code>ordinal</code> 换算出商品 ID，再回到倒排索引取原文。
        </p>
      </footer>
    </div>
  )
}

function Results({ data, loading }) {
  if (loading) {
    return <p className="notice">检索中…</p>
  }
  if (!data) {
    return <p className="notice">输入查询词后按回车。</p>
  }
  if (!data.hits.length) {
    return <p className="notice">{data.note || '没有命中。'}</p>
  }

  return (
    <section className="results">
      <p className="stats">
        {data.hits.length} 条命中
        <span className="sep" />
        {data.tookMs} ms
        {data.terms?.length > 0 && (
          <>
            <span className="sep" />
            分词 {data.terms.join(' / ')}
          </>
        )}
      </p>

      {/*
        key 绑定查询词：React 会复用同一个 <ol>，不换 key 的话 CSS animation 只播第一次，
        「回应搜索动作的那一次亮相」就失效了。
      */}
      <ol className="hits" key={data.query}>
        {data.hits.map((hit) => (
          <Hit key={`${hit.rank}-${hit.productId}`} hit={hit} terms={data.terms} />
        ))}
      </ol>

      {data.note && data.hits.length > 0 && <p className="notice notice-quiet">{data.note}</p>}
    </section>
  )
}

function Hit({ hit, terms }) {
  return (
    <li className="hit">
      <span className="rank">{hit.rank}</span>

      <span className="body">
        <span className="title">
          {/* null 表示这个商品在倒排索引里已被软删除 —— 向量索引里还留着它的向量 */}
          {hit.title ? mark(hit.title, terms) : <em className="gone">该商品已从索引中删除</em>}
        </span>
        <span className="attrs">
          <span>商品 ID {hit.productId}</span>
          {hit.ordinal != null && <span>ordinal {hit.ordinal}</span>}
          {hit.docId != null && <span>doc {hit.docId}</span>}
        </span>
      </span>

      <span className="score">{hit.score.toFixed(hit.ordinal != null ? 4 : 2)}</span>
    </li>
  )
}

/**
 * 把标题里命中查询词的部分标出来。
 *
 * 按词长降序匹配，避免短词先命中把长词切碎；分词结果由后端给出，
 * 前端不自己猜，保证标出来的就是真正参与打分的那几个词。
 */
function mark(title, terms) {
  const unique = [...new Set(terms || [])].filter(Boolean).sort((a, b) => b.length - a.length)
  if (!unique.length) return title

  const pattern = unique.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')
  const set = new Set(unique)

  return title.split(new RegExp(`(${pattern})`, 'g')).map((part, i) =>
    set.has(part) ? <mark key={i}>{part}</mark> : part,
  )
}

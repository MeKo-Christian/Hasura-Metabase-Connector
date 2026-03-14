-- Seed schema for the Hasura-Metabase-Connector integration stack.
--
-- Schema design covers:
--   - normal tables: authors, articles, tags, article_tags, orders, order_items
--   - one view: article_stats
--   - one object relationship: articles.author_id -> authors.id
--   - one array relationship: authors -> articles (tracked in Hasura metadata)
--   - one aggregate-friendly fact table: order_items

SET client_min_messages = warning;

-- ─────────────────────────────────────────────────────────────────────────────
-- Tables
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE authors (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    email      TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE articles (
    id         SERIAL PRIMARY KEY,
    title      TEXT NOT NULL,
    body       TEXT,
    author_id  INTEGER REFERENCES authors (id) ON DELETE SET NULL,
    published  BOOLEAN NOT NULL DEFAULT FALSE,
    word_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tags (
    id   SERIAL PRIMARY KEY,
    name TEXT UNIQUE NOT NULL
);

-- Junction table: enables many-to-many between articles and tags.
CREATE TABLE article_tags (
    article_id INTEGER REFERENCES articles (id) ON DELETE CASCADE,
    tag_id     INTEGER REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (article_id, tag_id)
);

CREATE TABLE orders (
    id            SERIAL PRIMARY KEY,
    customer_name TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Aggregate-friendly fact table: many numeric columns for sum/avg/min/max tests.
CREATE TABLE order_items (
    id           SERIAL PRIMARY KEY,
    order_id     INTEGER REFERENCES orders (id) ON DELETE CASCADE,
    product_name TEXT NOT NULL,
    quantity     INTEGER NOT NULL DEFAULT 1,
    unit_price   NUMERIC(10, 2) NOT NULL,
    discount     NUMERIC(5, 2) NOT NULL DEFAULT 0.00
);

-- ─────────────────────────────────────────────────────────────────────────────
-- View (aggregation-friendly, exercised by sync tests)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE VIEW article_stats AS
SELECT
    a.author_id,
    au.name                                              AS author_name,
    COUNT(a.id)                                         AS article_count,
    SUM(CASE WHEN a.published THEN 1 ELSE 0 END)        AS published_count,
    AVG(a.word_count)                                   AS avg_word_count
FROM articles a
JOIN authors au ON a.author_id = au.id
GROUP BY a.author_id, au.name;

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed data
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO authors (name, email) VALUES
    ('Alice Smith',  'alice@example.com'),
    ('Bob Jones',    'bob@example.com'),
    ('Carol White',  'carol@example.com');

INSERT INTO articles (title, body, author_id, published, word_count) VALUES
    ('Introduction to GraphQL',    'GraphQL is a query language for APIs.',        1, TRUE,  320),
    ('Hasura Deep Dive',           'Hasura is a GraphQL engine over Postgres.',     1, TRUE,  870),
    ('Metabase for Analytics',     'Metabase is an open-source BI tool.',           2, FALSE, 410),
    ('Database Optimisation',      'Tips for writing fast SQL queries.',            2, TRUE,  650),
    ('Future of APIs',             'Comparing REST, GraphQL, and gRPC.',            3, TRUE,  530),
    ('Getting Started with Hasura','Step-by-step Hasura setup guide.',              3, FALSE, 290);

INSERT INTO tags (name) VALUES
    ('graphql'), ('database'), ('api'), ('analytics'), ('hasura'), ('performance');

INSERT INTO article_tags (article_id, tag_id) VALUES
    (1, 1), (1, 3),
    (2, 1), (2, 5),
    (3, 4),
    (4, 2), (4, 6),
    (5, 1), (5, 3),
    (6, 5), (6, 1);

INSERT INTO orders (customer_name, status) VALUES
    ('Alice Smith',  'completed'),
    ('Bob Jones',    'completed'),
    ('Carol White',  'pending'),
    ('Alice Smith',  'completed'),
    ('Bob Jones',    'shipped');

INSERT INTO order_items (order_id, product_name, quantity, unit_price, discount) VALUES
    (1, 'Widget A',   2,  9.99, 0.00),
    (1, 'Gadget B',   1, 24.99, 2.00),
    (2, 'Widget A',   5,  9.99, 0.00),
    (2, 'Tool C',     1, 49.99, 5.00),
    (3, 'Gadget B',   2, 24.99, 0.00),
    (4, 'Widget A',   3,  9.99, 1.00),
    (4, 'Gadget B',   1, 24.99, 0.00),
    (4, 'Tool C',     2, 49.99, 5.00),
    (5, 'Widget A',  10,  9.99, 0.50),
    (5, 'Luxury D',   1, 199.99, 20.00);

# SimpleDB

**Protocol:** Query (XML)
**Endpoint:** `POST /` with `Action=…`, signed for `sdb` (or with SigV2 form parameters)

SimpleDB stores items in domains. Each attribute of an item holds a set of string values.

## Items

`PutAttributes` adds values to attributes, and `Replace` swaps an attribute's values for the new ones. An item holds
at most 256 name-value pairs, and names and values are at most 1024 bytes. A put or delete can be conditional:

- `Expected.Exists=false` requires the attribute to be absent.
- `Expected.Value` requires the attribute's single value to be that value.

A failed condition answers `ConditionalCheckFailed`, a missing attribute `AttributeDoesNotExist`, and a multi-valued
one `MultiValuedAttribute`.

`DeleteAttributes` removes the values it names, the attributes it names, or the whole item. Batches take up to 25
items, each named once. `DomainMetadata` is counted from the items. Every read is consistent.

## Select

`select output from domain [where …] [order by attribute [asc|desc]] [limit n]`. The output is `*`, `itemName()`,
`count(*)` or a list of attributes. Comparisons are lexicographic, as SimpleDB stores strings.

A predicate on a multi-valued attribute holds when any value matches, and with `every(attribute)` only when all do.
Predicates test `=`, `!=`, `<`, `<=`, `>`, `>=`, `like`, `not like`, `between`, `in`, `is null` and `is not null`, on
attributes or `itemName()`. They combine with `and`, `or`, `not`, `intersection` and parentheses.

A sort attribute must appear in a predicate (`InvalidSortExpression`). `limit` is 100 by default and 2500 at most,
with `NextToken` for the next page. Errors come in SimpleDB's own `Response/Errors/Error` form.

## Supported Operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Create a domain |
| `DeleteDomain` | Delete a domain |
| `ListDomains` | List domains |
| `DomainMetadata` | Count a domain's items, attributes and values |
| `PutAttributes` | Add or replace values, optionally on a condition |
| `BatchPutAttributes` | Put up to 25 items |
| `DeleteAttributes` | Remove values, attributes or an item, optionally on a condition |
| `BatchDeleteAttributes` | Delete from up to 25 items |
| `GetAttributes` | Read an item's attributes |
| `Select` | Query items with the select language |
<!-- floci:actions:end -->

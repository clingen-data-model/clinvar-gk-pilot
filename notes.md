

Weird thing when exporting variation_identity table to JSON is that '>' character gets encoded as UTF-8 unicode '\u003e' instead of the ASCII character '>' (hex: 3e).

SELECT TO_HEX(CAST(nucleotide_expression AS bytes))
FROM
(SELECT
  content,
  JSON_EXTRACT_SCALAR(hgvs, "$['NucleotideExpression']['Expression']['$']") as nucleotide_expression
FROM
  (select content,
  JSON_EXTRACT_ARRAY(content, "$.HGVSlist.HGVS") as hgvs -- ['NucleotideExpression']['Expression']['$']
  FROM `clingen-stage.clinvar_2023_04_10_v1_6_58.variation`
  WHERE variation_type = "single nucleotide variant")
CROSS JOIN UNNEST(hgvs) as hgvs
WHERE content like '%NucleotideExpression%'
)
LIMIT 100

Take one example from the output of the above:

4e475f3030383732372e323a672e313337383139413e47

(NG_008727.2:g.137819A>G)

These are all 1 byte ascii encoded characters. The 3e in the 4th and 3rd to last positions are the '>' character. Which for some reason when exported to JSON from bigquery is encoded as a 2 byte unicode sequence '003e' instead of '3e', and the JSON conversion embeds it as '\u003e' since it isn't ASCII.

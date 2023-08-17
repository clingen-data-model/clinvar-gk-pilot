

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

=====

Fields in variation_identity:
id
name
subclass_type
variation_type
canonical_spdi
hgvs {
  assembly
  nucleotide
}
cyto
seq {
  assembly
  accession
  chr
  disp_start
  disp_stop
  start
  stop
  inner_start
  inner_stop
  outer_start
  outer_stop
  ref_allele_vcf
  alt_allele_vcf
  position_vcf
  variant_length
}
derived_hgvs
absolute_copies
min_copies
max_copies
xrefs
vrs_xform_plan {
  type
  inputs
  policy
}

===== From the ticket:
https://app.zenhub.com/workspaces/genegraphdxclinvar-60340fb9898dae001107e94e/issues/gh/clingen-data-model/genegraph/778

in the vrs_xform_plan, the type field contains one of (Allele, CopyNumberCount, CopyNumberChange, Text|Unsupported)
all enums of type from data: [Allele, CopyNumberChange, CopyNumberCount, DefiniteRange, IndefiniteRange, LiteralSequenceExpression, Number, Text]
all enums of policy from data: [Absolute copy count, Canonical SPDI, Copy number change (cn loss|del and cn gain|dup), Genotype/Haplotype, Invalid/unsupported hgvs, Min/max copy count range not supported, NCBI36 genomic only, No hgvs or location info, Remaining valid hgvs alleles]
all enums of inputs from data: [absolute_copies, canonical_spdi, hgvs, id]

for Allele types use the translate_from api
for CopyNumberCount use the hgvs_to_copy_number_count
for CopyNumberChange use the hgvs_to_copy_number_change` (and the appropriate EFO code)

Note: Ignore all Text|Unsupported variations once we start moving to the 2.0alpha schema since we will not be dealing with Text variation the same way.
====
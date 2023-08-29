Tabix is meant for indexing gff/vcf tabular files with a column for sequence and position.

But it can index anything in tabular format, if you can contrive a "sequence" and position for each record.

Lets say we have a newline-delimited (JSONL/NDJSON) file with VRS variants and their originating information, such as a database id (e.g. ClinVar Variation ID).

e.g. a file called `normalized.ndjson``:
```ndjson
{"in":{"seq":{"stop":"7222826","disp_start":"7222826","chr":"17","ref_allele_vcf":"CG","position_vcf":"7222825","accession":"NC_000017.11","assembly":"GRCh38","start":"7222826","variant_length":"1","disp_stop":"7222826","alt_allele_vcf":"C"},"vrs_xform_plan":{"type":"Allele","inputs":["canonical_spdi"],"policy":"Canonical SPDI"},"xrefs":"dbSNP:2071295244","id":"932848","name":"NM_000018.4(ACADVL):c.1039del (p.Ala347fs)","hgvs":{"assembly":"GRCh38","nucleotide":"NC_000017.11:g.7222827del"},"subclass_type":"SimpleAllele","canonical_spdi":"NC_000017.11:7222825:GG:G","variation_type":"Deletion","derived_hgvs":"NC_000017.11:g.7222826del","cyto":"17p13.1"},"out":{"id":"ga4gh:VA.vRUj-LS-12yJYZ8xdUQ5g9YLKnCYq-PJ","type":"Allele","location":{"id":"ga4gh:SL.A0vLyhj-Nb6evlt3ELql2FTjw1EAyWQV","type":"SequenceLocation","sequence_id":"ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7","start":{"type":"Number","value":7222825},"end":{"type":"Number","value":7222827}},"state":{"type":"LiteralSequenceExpression","sequence":"G"}}}
{"in":{"seq":{"stop":"7220183","disp_start":"7220183","chr":"17","ref_allele_vcf":"CG","position_vcf":"7220182","accession":"NC_000017.11","assembly":"GRCh38","start":"7220183","variant_length":"1","disp_stop":"7220183","alt_allele_vcf":"C"},"vrs_xform_plan":{"type":"Allele","inputs":["canonical_spdi"],"policy":"Canonical SPDI"},"xrefs":"dbSNP:2071123075","id":"857574","name":"NM_000018.4(ACADVL):c.128del (p.Gly43fs)","hgvs":{"assembly":"GRCh38","nucleotide":"NC_000017.11:g.7220187del"},"subclass_type":"SimpleAllele","canonical_spdi":"NC_000017.11:7220182:GGGGG:GGGG","variation_type":"Deletion","derived_hgvs":"NC_000017.11:g.7220183del","cyto":"17p13.1"},"out":{"id":"ga4gh:VA.xvqHYJTWjYBp0cuOaS9RcSU9gt5VWPAT","type":"Allele","location":{"id":"ga4gh:SL.QGDVQgGJZYlT3XtyptbZdxCS1u5xZkv3","type":"SequenceLocation","sequence_id":"ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7","start":{"type":"Number","value":7220182},"end":{"type":"Number","value":7220187}},"state":{"type":"LiteralSequenceExpression","sequence":"GGGG"}}}
```

We can turn this into a tabular file that is in a form tabix can deal with if we some up with a sequence and position for each of these records, which is something a user will know when querying the file. So let's use the ClinVar ID as the sequence, and 0 as the position.

```clojure
(let [input-filename "normalized.ndjson"]
  (with-open [rdr (io/reader input-filename)
              wtr (io/writer (str "tabular-" input-filename))]
    (doseq [line (line-seq rdr)]
      (let [j (charred/read-json line)
            variation-id (get-in j ["in" "id"])]
        (.write wtr (str/join "\t" [variation-id "0" (get j "out")]))
        (.write wtr "\n")))))
```

This generates a file like this, called `tabular-normalized.ndjson`, with just the VRS normalized variants as the entry values:

```
932848	0	{"id" "ga4gh:VA.vRUj-LS-12yJYZ8xdUQ5g9YLKnCYq-PJ", "location" {"sequence_id" "ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7", "id" "ga4gh:SL.A0vLyhj-Nb6evlt3ELql2FTjw1EAyWQV", "start" {"value" 7222825, "type" "Number"}, "type" "SequenceLocation", "end" {"value" 7222827, "type" "Number"}}, "type" "Allele", "state" {"sequence" "G", "type" "LiteralSequenceExpression"}}
857574	0	{"id" "ga4gh:VA.xvqHYJTWjYBp0cuOaS9RcSU9gt5VWPAT", "location" {"sequence_id" "ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7", "id" "ga4gh:SL.QGDVQgGJZYlT3XtyptbZdxCS1u5xZkv3", "start" {"value" 7220182, "type" "Number"}, "type" "SequenceLocation", "end" {"value" 7220187, "type" "Number"}}, "type" "Allele", "state" {"sequence" "GGGG", "type" "LiteralSequenceExpression"}}
```

Using bgzip (creates blocked-gzip files, which is just a normal gzip file with the constraint that every gzip block begins where a line in the input file begins, so if you seek to the beginning of a block, you are guaranteed to be at the first byte of a line of the input file):

```sh
bgzip -c tabular-normalized.ndjson > tabular-normalized.ndjson.gz
```

Using tabix, with some specific options set so it knows what to do with this:

```sh
tabix -f -s 1 -b 2 -e 2 -0 tabular-normalized.ndjson.gz
```

This creates an index file `tabular-normalized.ndjson.gz.tbi`, which tabix uses during queries to get the byte offsets to jump to in the compressed file.

Now we can query `tabular-normalized.ndjson.gz` for ClinVar variation IDs, pretending the ID is a sequence name, and using 0 as the position in every query.

e.g. query for variation id 932848
```
$ tabix tabular-normalized.ndjson.gz 932848:0
932848	0	{"id" "ga4gh:VA.vRUj-LS-12yJYZ8xdUQ5g9YLKnCYq-PJ", "location" {"sequence_id" "ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7", "id" "ga4gh:SL.A0vLyhj-Nb6evlt3ELql2FTjw1EAyWQV", "start" {"value" 7222825, "type" "Number"}, "type" "SequenceLocation", "end" {"value" 7222827, "type" "Number"}}, "type" "Allele", "state" {"sequence" "G", "type" "LiteralSequenceExpression"}}
```

The problem is that the sequence (here clinvar variation id) column is not indexed performantly, because the assumption tabix makes is that there are not many sequences, but a lot of positions on them.

So lets flip it, use the clinvar ID as the position, since it is already a number, and use 0 as the sequence.

```clojure
(let [input-filename "normalized.ndjson"]
  (with-open [rdr (io/reader input-filename)
              wtr (io/writer (str "tabular-" input-filename))]
    (doseq [line (line-seq rdr)]
      (let [j (charred/read-json line)
            variation-id (get-in j ["in" "id"])]
        (.write wtr (str/join "\t" ["0" variation-id (get j "out")]))
        (.write wtr "\n")))))
```

Tabix (and technically VCF) requires positions to be sorted, and now that they're not all 0 we have to sort them.

```sh
sort -k 2 -n tabular-normalized.ndjson > sorted-tabular-normalized.ndjson
```

Now we can re-run bgzip and tabix indexing on the sorted file.

```sh
$ bgzip -c sorted-tabular-normalized.ndjson > sorted-tabular-normalized.ndjson.gz

$ tabix -f -s 1 -b 2 -e 2 -0 sorted-tabular-normalized.ndjson.gz
```

And query using 0 as the sequence and the clinvar id as the single position

```sh
$ tabix sorted-tabular-normalized.ndjson.gz 0:932848-932848
0	932847	{"id" "ga4gh:VA.hW3fZd501P1oJpCe6lmwt9JEqsGGepgR", "location" {"sequence_id" "ga4gh:SQ.dLZ15tNO1Ur0IcGjwc3Sdi_0A6Yf4zm7", "id" "ga4gh:SL.eKZWRbEwuIbEtJ4qSIS9as9h4iiQeULx", "start" {"value" 7222711, "type" "Number"}, "type" "SequenceLocation", "end" {"value" 7222715, "type" "Number"}}, "type" "Allele", "state" {"sequence" "AG", "type" "LiteralSequenceExpression"}}
```

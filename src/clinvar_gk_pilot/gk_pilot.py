"""
Writes ndjson or a single large json graph.
Stores data files (stmts, catvars, ctxvars and seqrefs)
 to in-memory sqlite DB and then processes statement data
 and obtains the other data types from the db.
"""
import sqlite3 as sql
import json

# SQL defs
create_table = "CREATE TABLE IF NOT EXISTS vrs_objects (vrs_id TEXT PRIMARY KEY, vrs_object JSONB)"
insert_query = "INSERT INTO vrs_objects (vrs_id, vrs_object) VALUES (?, ?) ON CONFLICT DO NOTHING;"
select_by_id_query = "select * from vrs_objects where vrs_id = ?"
select_all_stmts = "select * from vrs_objects where vrs_id like 'stmts%'"

base_dir="/Users/toneill/dev/git/clinvar-gk-pilot/"

# files and database key lookup prefixes
files_and_types = {
    f"{base_dir}/output-stmts_before.json": "stmts.json#/",
    f"{base_dir}/output-seqrefs_before.json": "seqrefs.json#/",
    f"{base_dir}/output-catvars_before.json": "catvars.json#/",
    f"{base_dir}/output-ctxvars_before.json":  "ctxvars.json#/"
}


def create_database() -> sql.Connection:
    con = sql.connect(":memory:")  # temporary choice
    con.execute(create_table)
    con.commit()
    return con


def add_types_to_database(connection: sql.Connection, files_and_prefixes: dict) -> None:
    for file, prefix in files_and_prefixes.items():
        with open(file, 'r') as f:
            d = json.load(f)
            for k in d.keys():
                connection.execute(insert_query, [prefix + k, json.dumps(d[k])])
                connection.commit()


def process_statement(connection: sql.Connection, row: tuple) -> tuple:
    stmt_id = row[0].split("/")[-1]
    print(f"Processing {stmt_id}...")
    stmt_json = json.loads(row[-1])
    variant = stmt_json['variant']

    # statement:    "variant": "catvars.json#/1153275"
    # categorical:  "definingContext": "ctxvars.json#/ga4gh:VA.zxk9o6AaQM1kfptiz45-bfmyMBPIlsic"
    # contextual:   "location": { "sequenceReference":"seqrefs.json#/NC_000021.9" ...}

    # lookup the categorical def from the variant (which at this point is in the
    # form 'catvars.json#/########')
    categorical_data = connection.execute(select_by_id_query, [variant]).fetchone()[-1]
    assert categorical_data, f"Missing categorical data from variant {variant}"
    categorical_json = json.loads(categorical_data)

    # lookup the contextual variant from definingContext in the categorical data
    # i.e. "definingContext": "ctxvars.json#/ga4gh:VA.zxk9o6AaQM1kfptiz45-bfmyMBPIlsic"
    if 'definingContext' in categorical_json:
        categorical_defining_context = categorical_json['definingContext']
        contextual_data = connection.execute(select_by_id_query, [categorical_defining_context]).fetchone()[-1]
        assert contextual_data, f"Missing contextual data key for variant {variant}"
        contextual_json = json.loads(contextual_data)

        # "location": {"sequenceReference": "seqrefs.json#/NC_000021.9"...}
        try:
            sequence_reference = contextual_json['location']['sequenceReference']
            sequence_data = connection.execute(select_by_id_query, [sequence_reference]).fetchone()[-1]
            assert sequence_data, f"Missing sequence '{sequence_reference}' for variant {variant}"
            sequence_json = json.loads(sequence_data)
            contextual_json['location']['sequenceReference'] = sequence_json
        except KeyError:
            print(f"Missing sequence reference for variant {variant}")
            raise

        # expand the references to be json equivalents
        categorical_json['definingContext'] = contextual_json
        stmt_json['variant'] = categorical_json

        # expand the members references array in the categorical data
        # which is input as:
        # "members": [
        #    "ctxvars.json#/LRG_482:g.1102626G>A",
        #    "ctxvars.json#/NC_000021.8:g.36259383C>T",
        #    ...
        #    "ctxvars.json#/ga4gh:VA.zxk9o6AaQM1kfptiz45-bfmyMBPIlsic"
        # ]
        # except for (in this instance the last one) that matches the
        # defining context. That we replace with a json pointer of the form:
        #
        #     {"$ref": "#/SCV000927861.1/variant/definingContext"}
        #
        members_json = []
        for member in categorical_json['members']:
            if member == categorical_defining_context:
                json_pointer = f'{{"$ref": "#/{stmt_id}/variant/definingContext"}}'
                json_pointer_as_json = json.loads(json_pointer)
                members_json.append(json_pointer_as_json)
            else:
                member_data = connection.execute(select_by_id_query, [member]).fetchone()[-1]
                member_json = json.loads(member_data)
                members_json.append(member_json)
        categorical_json['members'] = members_json
    else:
        # only expand the statements' categorical reference
        stmt_json['variant'] = categorical_json
    return stmt_id, stmt_json


def get_all_statements() -> dict:
    con = create_database()
    add_types_to_database(con, files_and_types)
    json_dict = {} # key = SCVID, value=dict
    for stmt in con.execute(select_all_stmts):
        stmt_id, stmt_json = process_statement(con, stmt)
        json_dict[stmt_id] = stmt_json
    return json_dict

def produce_json_from_files() -> list:
    con = create_database()
    add_types_to_database(con, files_and_types)

    # process all statements and lookup other references in db
    json_output = []
    for stmt in con.execute(select_all_stmts):
        stmt_id, stmt_json = process_statement(con, stmt)
        json_output.append(json.dumps({stmt_id: stmt_json}))
    return json_output


def write_ndjson_file(filename: str) -> None:
    with open(filename, 'w') as fp:
        for j in produce_json_from_files():
            fp.write(j)
            fp.write("\n")


def write_unified_json_file(filename: str) -> None:
    stmts = get_all_statements()
    with open(filename, 'w') as fp:
        json.dump(stmts, fp, indent=4)  # pretty print


def main():
    """
    ToDO - Add command line processing of args
           for various outputs.
    """
    # write_ndjson_file(filename="gk-pilot.ndjson")
    write_unified_json_file(filename="gk-pilot.json")


if __name__ == '__main__':
    main()



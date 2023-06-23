import sys
import argparse
import json
from concurrent.futures import ThreadPoolExecutor
import threading
from queue import Queue
from typing import List

import requests


def parse_args(args: List[str]) -> dict:
    """
    Parse arguments and return as dict
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--filename",
                        required=True,
                        help="Filename to read")
    parser.add_argument("--normalizer-url",
                        default="https://normalization.clingen.app/variation",
                        help="Variation Normalization base url")
    return vars(parser.parse_args(args))


def process_line(record: dict, opts: dict, output_queue: Queue = None) -> dict:
    """
    Process one line and return normalized
    """
    def validate_response(resp):
        if resp.status_code != 200:
            raise RuntimeError(
                "Response status was not 200: " + str(vars(resp)))
        j = json.loads(resp.text)
        if len(j.get("errors", [])) > 0:
            raise RuntimeError(
                "Response had errors: " + str(j))
        return j

    var = None
    if "canonical_spdi" in record:
        url = opts["normalizer_url"] + "/translate_from"
        resp = requests.get(url,
                            params={"variation": record["canonical_spdi"],
                                    "fmt": "spdi"},
                            headers={"Accept": "application/json"},
                            timeout=30)
        obj = validate_response(resp)
        var = obj["variation"]
    # Blocks if output queue is full
    if output_queue:
        output_queue.put(var)
    return var


def worker_target(inputs_queue: Queue, outputs_queue: Queue):
    with ThreadPoolExecutor() as executor:
        while True:
            [val, opts] = inputs_queue.get()
            if val is None:
                break
            # Execute in an async pool, with a callback that puts
            # it on the output queue when done
            fut = executor.submit(process_line, val, opts)
            fut.add_done_callback(
                lambda fut: outputs_queue.put(fut.result()))


def handle_outputs(outputs_queue: Queue):
    """
    Do something with the outputs
    """
    while True:
        variation = outputs_queue.get()
        if variation is None:
            break
        print(json.dumps(variation))


def main(args):
    """
    Main entrypoint for normalizing a variation_identity file
    """
    opts = parse_args(args)

    inputs_queue = Queue(100)
    outputs_queue = Queue(100)
    t = threading.Thread(
        target=worker_target,
        args=[inputs_queue, outputs_queue])
    t.start()

    t2 = threading.Thread(
        target=handle_outputs,
        args=[outputs_queue]
    )
    t2.start()

    with open(opts["filename"], encoding="UTF-8") as f_in:
        counter = 0
        for line in f_in:
            record = json.loads(line)
            inputs_queue.put((record, opts))
            output = process_line(record, opts)
            if output is not None:
                print(output)
                counter += 1
            if counter >= 400:
                break

    # Signal worker that inputs have ended
    inputs_queue.put([None, None])
    outputs_queue.put([None, None])
    t.join()
    t2.join()


def main_single_thread(args):
    """
    Main entrypoint for normalizing a variation_identity file
    """
    opts = parse_args(args)
    with open(opts["filename"], encoding="UTF-8") as f_in:
        counter = 0
        for line in f_in:
            record = json.loads(line)
            output = process_line(record, opts)
            if output is not None:
                print(output)
                counter += 1
            if counter >= 100:
                break


if __name__ == "__main__":
    main(sys.argv[1:])

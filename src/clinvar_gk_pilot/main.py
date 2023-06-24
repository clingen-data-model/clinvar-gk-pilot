import sys
import argparse
import json
from concurrent.futures import (
    ThreadPoolExecutor,
    ProcessPoolExecutor)
import threading
import multiprocessing as mp
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


def process_line(record: dict,
                 opts: dict,
                 output_queue: mp.Queue) -> dict:
    """
    Process one line and return normalized
    """
    print("in process_line")

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
        print("process_line got a canonical_spdi")
        url = opts["normalizer_url"] + "/translate_from"
        resp = requests.get(url,
                            params={"variation": record["canonical_spdi"],
                                    "fmt": "spdi"},
                            headers={"Accept": "application/json"},
                            timeout=30)
        obj = validate_response(resp)
        var = obj["variation"]
    # Blocks if output queue is full
    if output_queue and var is not None:
        print("Adding return value to output queue")
        output_queue.put(var)
    print("returning from process_line")
    return var


def worker_target(inputs_queue: mp.Queue,
                  outputs_queue: mp.Queue):
    """
    Target function for worker thread that processes individual records
    """
    with ProcessPoolExecutor(max_workers=10) as executor:
        while True:
            inp = inputs_queue.get()
            if inp is None:
                break
            [val, opts] = inp
            print("Worker got: " + str(inp))
            # Execute in an async pool, with a callback that puts
            # it on the output queue when done
            fut = executor.submit(process_line,
                                  val,
                                  opts,
                                  outputs_queue)
            print("submitted task to pool")
            print("Waiting for response")
            print("result: " + str(fut.result()))

            # fut.add_done_callback(
            #     lambda fut: outputs_queue.put(fut.result()))
        executor.shutdown(wait=True)


def handle_outputs(outputs_queue: Queue, counter: mp.Value):
    """
    Do something with the outputs
    """
    print("entering handle_outputs")
    while True:
        variation = outputs_queue.get()
        if variation is None:
            print("Shutting down handle_outputs")
            break
        print("handle_outputs got an output value: " + str(variation))
        with counter.get_lock():
            counter.value += 1
        print(json.dumps(variation))


def main_multiprocessing(args):
    """
    Main entrypoint for normalizing a variation_identity file
    """
    opts = parse_args(args)
    mp_manager = mp.Manager()

    inputs_queue = mp_manager.Queue(100)
    outputs_queue = mp_manager.Queue(100)
    # input_counter = mp.Value("i", 0, lock=True)
    t = mp.Process(
        target=worker_target,
        args=(inputs_queue, outputs_queue))
    t.start()

    output_counter = mp.Value("i", 0, lock=True)
    t2 = mp.Process(
        target=handle_outputs,
        args=(outputs_queue, output_counter)
    )
    t2.start()
    import time
    time.sleep(5)

    counter = 0
    with open(opts["filename"], encoding="UTF-8") as f_in:
        for line in f_in:
            record = json.loads(line)
            if "canonical_spdi" in record:
                print("record had canonical_spdi")
            inputs_queue.put((record, opts))
            counter += 1
            if output_counter.value >= 100:
                print("Processed to limit")
                break

    # Signal worker that inputs have ended
    inputs_queue.put(None)
    outputs_queue.put(None)
    t.join()
    t2.join()


# def main_threading(args):
#     """
#     Main entrypoint for normalizing a variation_identity file
#     """
#     opts = parse_args(args)

#     inputs_queue = Queue(100)
#     outputs_queue = Queue(100)
#     t = threading.Thread(
#         target=worker_target,
#         args=[inputs_queue, outputs_queue])
#     t.start()

#     t2 = threading.Thread(
#         target=handle_outputs,
#         args=[outputs_queue]
#     )
#     t2.start()

#     with open(opts["filename"], encoding="UTF-8") as f_in:
#         counter = 0
#         for line in f_in:
#             record = json.loads(line)
#             inputs_queue.put((record, opts))
#             output = process_line(record, opts)
#             if output is not None:
#                 print(output)
#                 counter += 1
#             if counter >= 400:
#                 break

#     # Signal worker that inputs have ended
#     inputs_queue.put([None, None])
#     outputs_queue.put(None)
#     t.join()
#     t2.join()


# def main_single_thread(args):
#     """
#     Main entrypoint for normalizing a variation_identity file
#     """
#     opts = parse_args(args)
#     with open(opts["filename"], encoding="UTF-8") as f_in:
#         counter = 0
#         for line in f_in:
#             record = json.loads(line)
#             output = process_line(record, opts)
#             if output is not None:
#                 print(output)
#                 counter += 1
#             if counter >= 100:
#                 break


def f1(arg1, arg2):
    print("arg1: " + str(arg1) + ", arg2: " + str(arg2))


if __name__ == "__main__":
    main_multiprocessing(sys.argv[1:])

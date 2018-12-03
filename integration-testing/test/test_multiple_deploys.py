import pytest
import conftest
import contextlib
import threading
import logging

from rnode_testing.rnode import (
    start_bootstrap,
    started_peer,
)
from rnode_testing.wait import (
    wait_for_blocks_count_at_least,
    wait_for_approved_block_received_handler_state,
)

from typing import Generator, TYPE_CHECKING

if TYPE_CHECKING:
    from _pytest.fixtures import SubRequest
    from conftest import System, ValidatorsData
    from docker.client import DockerClient
    from rnode_testing.rnode import Node

BOOTSTRAP_NODE_KEYS = conftest.KeyPair(private_key='80366db5fbb8dad7946f27037422715e4176dda41d582224db87b6c3b783d709', public_key='1cd8bf79a2c1bd0afa160f6cdfeb8597257e48135c9bf5e4823f2875a1492c97')
BONDED_VALIDATOR_KEY_1 = conftest.KeyPair(private_key='120d42175739387af0264921bb117e4c4c05fbe2ce5410031e8b158c6e414bb5', public_key='02ab69930f74b931209df3ce54e3993674ab3e7c98f715608a5e74048b332821')
BONDED_VALIDATOR_KEY_2 = conftest.KeyPair(private_key='120d42175739387af0264921bb117e4c4c05fbe2ce5410031e8b158c6e414bb5', public_key='02ab69930f74b931209df3ce54e3993674ab3e7c98f715608a5e74048b332821')
BONDED_VALIDATOR_KEY_3 = conftest.KeyPair(private_key='120d42175739387af0264921bb117e4c4c05fbe2ce5410031e8b158c6e414bb5', public_key='02ab69930f74b931209df3ce54e3993674ab3e7c98f715608a5e74048b332821')

@pytest.yield_fixture(scope='session')
def validators_config():
    validator_keys = [BONDED_VALIDATOR_KEY_1, BONDED_VALIDATOR_KEY_2, BONDED_VALIDATOR_KEY_3]
    with conftest.temporary_bonds_file(validator_keys) as f:
        yield conftest.ValidatorsData(bonds_file=f, bootstrap_keys=BOOTSTRAP_NODE_KEYS, peers_keys=validator_keys)


@pytest.yield_fixture(scope="session")
def custom_system(request, validators_config, docker_client_session):
    test_config = conftest.make_test_config(request)
    yield conftest.System(test_config, docker_client_session, validators_config)

@contextlib.contextmanager
def started_bonded_validator(system: "System", bootstrap_node: "Node", no, key_pair) -> Generator["Node", None, None]:
    with started_peer(
        system.config.node_startup_timeout,
        docker_client=system.docker,
        network=bootstrap_node.network,
        name='bonded-validator-' + str(no),
        bonds_file=system.validators_data.bonds_file,
        rnode_timeout=system.config.rnode_timeout,
        bootstrap=bootstrap_node,
        key_pair=key_pair,
    ) as bonded_validator:
        wait_for_approved_block_received_handler_state(bonded_validator, system.config.node_startup_timeout)
        yield bonded_validator

def setup_condition(node, count):
    def condition():
        current_block_no = node.get_blocks_count(30)
        if current_block_no < count:
            raise Exception("Expected block no {}, got block no {}".format(count, current_block_no))
    return condition

@pytest.mark.xfail
def test_multiple_deploys_at_once(custom_system : "System") -> None:
    with start_bootstrap(custom_system.docker, custom_system.config.node_startup_timeout, custom_system.config.rnode_timeout, custom_system.validators_data, mount_dir=custom_system.config.mount_dir) as bootstrap_node:
        with started_bonded_validator(custom_system, bootstrap_node, 1, BONDED_VALIDATOR_KEY_1) as no1:
            with started_bonded_validator(custom_system, bootstrap_node, 2, BONDED_VALIDATOR_KEY_2) as no2:
                with started_bonded_validator(custom_system, bootstrap_node, 3, BONDED_VALIDATOR_KEY_3) as no3:
                    contract_path = '/opt/docker/examples/hello_world_again.rho'
                    amount = 1
                    deploy1 = deployThread("node1", no1, contract_path, amount)
                    deploy1.start()

                    expected_blocks_count = 1
                    max_retrieved_blocks = 1
                    wait_for_blocks_count_at_least(
                        no1,
                        expected_blocks_count,
                        max_retrieved_blocks,
                        expected_blocks_count * 10,
                    )

                    deploy2 = deployThread("node2", no2, contract_path, 3)
                    deploy2.start()

                    deploy3 = deployThread("node3", no3, contract_path, 3)
                    deploy3.start()

                    expected_blocks_count = 7
                    max_retrieved_blocks = 7
                    wait_for_blocks_count_at_least(
                        no1,
                        expected_blocks_count,
                        max_retrieved_blocks,
                        480
                    )
                    wait_for_blocks_count_at_least(
                        no2,
                        expected_blocks_count,
                        max_retrieved_blocks,
                        expected_blocks_count * 10,
                    )
                    wait_for_blocks_count_at_least(
                        no3,
                        expected_blocks_count,
                        max_retrieved_blocks,
                        expected_blocks_count * 10,
                    )

                    deploy1.join()
                    deploy2.join()
                    deploy3.join()

class deployThread (threading.Thread):
    def __init__(self, name, node, contract, count):
        threading.Thread.__init__(self)
        self.name = name
        self.node = node
        self.contract = contract
        self.count = count
        logging.info(f"Setup thread - {self.contract} to node {self.name}, amount {count}.")
    def run(self):
        for i in range(self.count):
            logging.info(f"[{self.name}]-[{i}] Will deploy {self.contract}.")
            d = self.node.deploy(self.contract)
            logging.info(f"[{self.name}]-[{i}] Deploy {self.contract}: {d}")
            p = self.node.propose()
            logging.info(f"[{self.name}]-[{i}] Proposed {self.contract}: {p}")
            s = self.node.show_blocks_with_depth(1)
            logging.info(f"[{self.name}]-[{i}] Show blocks: {s}")
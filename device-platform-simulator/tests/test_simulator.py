import pathlib
import sys
import unittest

SIMULATOR_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SIMULATOR_ROOT))

import simulator


class SimulatorTest(unittest.TestCase):
    def test_duplicate_delivery_keeps_the_same_event_id_and_payload(self):
        first, second = simulator.scenario("duplicate_delivery", "event-fixed")

        self.assertEqual(first, second)
        self.assertEqual("event-fixed", first["eventId"])

    def test_scheduled_issue_uses_device_time_as_expected_transmission_time(self):
        event = simulator.scenario("scheduled_missed", "event-fixed")[0]

        self.assertEqual("2026-09-04T00:00:00Z", event["payload"]["expectedTransmissionAt"])
        self.assertEqual("2026-09-05T00:00:00Z", event["payload"]["nextTransmissionAt"])
        self.assertEqual(
            "MONITORING:sim-device-001:1788480000", event["correlationRef"]
        )

    def test_low_battery_has_its_own_customer_service_correlation(self):
        event = simulator.scenario("low_battery", "event-fixed")[0]

        self.assertEqual("LOW_BATTERY", event["payload"]["issueType"])
        self.assertEqual(
            "BATTERY:sim-device-001:ICM_PATIENT_SERVICE:900001",
            event["correlationRef"],
        )
        self.assertEqual(
            event["correlationRef"],
            "BATTERY:" + event["payload"]["deviceRef"] + ":" + event["payload"]["sourceRecordRef"],
        )

    def test_partial_transmission_contains_expected_and_received_counts(self):
        event = simulator.scenario("partial_transmission", "event-fixed")[0]

        self.assertEqual("ISSUE_DETECTED", event["eventType"])
        self.assertEqual("TRANSMISSION_EVENTS_INCOMPLETE", event["payload"]["issueType"])
        self.assertEqual(10, event["payload"]["totalEventCount"])
        self.assertEqual(6, event["payload"]["receivedEventCount"])

    def test_scheduled_missed_batch_uses_unique_devices_and_incident_keys(self):
        events = simulator.scheduled_missed_batch(3, "load-test")

        self.assertEqual(3, len(events))
        self.assertEqual(
            ["load-test-00001", "load-test-00002", "load-test-00003"],
            [event["payload"]["deviceRef"] for event in events],
        )
        self.assertEqual(3, len({event["eventId"] for event in events}))
        self.assertEqual(3, len({event["correlationRef"] for event in events}))
        self.assertTrue(all(event["eventType"] == "ISSUE_DETECTED" for event in events))

    def test_scheduled_missed_batch_rejects_non_positive_count(self):
        with self.assertRaisesRegex(ValueError, "count must be positive"):
            simulator.scheduled_missed_batch(0)


if __name__ == "__main__":
    unittest.main()

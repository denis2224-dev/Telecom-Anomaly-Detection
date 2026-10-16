ALTER TABLE app.scenario_commands DROP CONSTRAINT scenario_commands_scenario_type_check;
ALTER TABLE app.scenario_commands ADD CONSTRAINT scenario_commands_scenario_type_check
    CHECK (scenario_type IN (
        'VOLTE_SETUP_FAILURE',
        'VOLTE_SETUP_DELAY',
        'VOLTE_MEDIA_QUALITY',
        'VOLTE_HANDOVER_FAILURE',
        'TRANSPORT_LINK_OVERLOAD',
        'TRANSPORT_PACKET_LOSS',
        'TRANSPORT_RTT_INCREASE',
        'TRANSPORT_CAPACITY_REDUCTION',
        'ROAMING_REGISTRATION_FAILURE',
        'ROAMING_DATA_FAILURE',
        'ROAMING_PARTNER_DEGRADATION',
        'MOBILE_DATA_ACTIVATION_FAILURE',
        'MOBILE_DATA_DNS_FAILURE',
        'SMS_DELIVERY_DELAY',
        'SMS_QUEUE_CONGESTION',
        'SMS_ROUTE_FAILURE',
        'NORMAL_CONTROL',
        'TELEMETRY_GAP',
        'VOLTE_IMS_OVERLOAD',
        'SMS_QUEUE_DELAY'
    ));

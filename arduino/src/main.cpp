    /* Edge Impulse Arduino examples
    * Copyright (c) 2021 EdgeImpulse Inc.
    *
    * Permission is hereby granted, free of charge, to any person obtaining a copy
    * of this software and associated documentation files (the "Software"), to deal
    * in the Software without restriction, including without limitation the rights
    * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    * copies of the Software, and to permit persons to whom the Software is
    * furnished to do so, subject to the following conditions:
    *
    * The above copyright notice and this permission notice shall be included in
    * all copies or substantial portions of the Software.
    *
    * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    * SOFTWARE.
    */

    /* Includes ---------------------------------------------------------------- */
    #include <SmartSlippers_inferencing.h>
    #include <Arduino_LSM9DS1.h>
    #include <ArduinoBLE.h>

    /* Constant defines -------------------------------------------------------- */
    #define CONVERT_G_TO_MS2 9.80665f
    #define MAX_ACCEPTED_RANGE 2.0f // starting 03/2022, models are generated setting range to +-2, but this example use Arudino library which set range to +-4g. If you are using an older model, ignore this value and use 4.0f instead

    /* Private variables ------------------------------------------------------- */
    static bool debug_nn = false; // Set this to true to see e.g. features generated from the raw signal
    static uint32_t run_inference_every_ms = 200;
    static rtos::Thread inference_thread(osPriorityLow);
    //static rtos::Thread inference_thread(osPriorityNormal);

    //                     200 * 3
    static float buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE] = {0};
    //                                      200 * 3
    static float inference_buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE];

    // Blink
    #define RED 22
    #define BLUE 24
    #define GREEN 23
    #define LED_PWR 25

    const int ledPin = LED_BUILTIN; // the number of the LED pin
    int ledState = LOW;
    unsigned long previousMillis = 0;
    String lastPredictionStr = "";
    String predictionStr = "";

    BLEDevice central;
    BLEService copatiService("eba7805c-b406-11ec-b909-0242ac120002");

    BLEIntCharacteristic copatiHoja("05ed8326-b407-11ec-b909-0242ac120002", BLERead | BLENotify);
    BLEIntCharacteristic copatiStopnice("f72e3316-b407-11ec-b909-0242ac120002", BLERead | BLENotify);
    BLEIntCharacteristic copatiTek("05f99232-b408-11ec-b909-0242ac120002", BLERead | BLENotify);
    BLEIntCharacteristic copatiIdle("f7a9b8d6-b408-11ec-b909-0242ac120002", BLERead | BLENotify);
    BLEIntCharacteristic copatiUncertain("44f709ee-d2bf-11ec-9d64-0242ac120002", BLERead | BLENotify);

    /* Forward declaration */
    void run_inference_background();
    void connect_BLE();
    void blink(long interval, int ledPin);
    /**
     * @brief      Arduino setup function
     */
    void setup()
    {
        delay(5000);
        // put your setup code here, to run once:
        Serial.begin(115200);
        Serial.println("Edge Impulse Inferencing Demo");

        if (!IMU.begin())
        {
            ei_printf("Failed to initialize IMU!\r\n");
        }
        else
        {
            ei_printf("IMU initialized\r\n");
        }

        if (!BLE.begin())
        {
            Serial.println("starting BLE failed!");
            while (1) {}
            
        }

        BLE.setLocalName("SmartSlippers");
        BLE.setDeviceName("SmartSlippers");
        BLE.setAdvertisedService(copatiService);

        copatiService.addCharacteristic(copatiHoja);
        copatiService.addCharacteristic(copatiStopnice);
        copatiService.addCharacteristic(copatiTek);
        copatiService.addCharacteristic(copatiIdle);
        copatiService.addCharacteristic(copatiUncertain);
        BLE.addService(copatiService);

        //BLE.setConnectionInterval(180, 300);
        //BLE.setAdvertisingInterval(80);

        BLE.advertise();
        Serial.println("Bluetooth device active, waiting for connections...");

        digitalWrite(RED, LOW);
        while (1)
        {
            blink(400, RED);
            central = BLE.central();
            if (central)
            {
                digitalWrite(RED, HIGH);
                digitalWrite(BLUE, HIGH);
                digitalWrite(GREEN, LOW);
                digitalWrite(LED_PWR, HIGH);

                Serial.print("Connected to central: ");
                Serial.println(central.address());
                break;
            }
        }

        if (EI_CLASSIFIER_RAW_SAMPLES_PER_FRAME != 3)
        {
            ei_printf("ERR: EI_CLASSIFIER_RAW_SAMPLES_PER_FRAME should be equal to 3 (the 3 sensor axes)\n");
            return;
        }
        inference_thread.start(mbed::callback(&run_inference_background));
    }

    /**
     * @brief      Printf function uses vsnprintf and output using Arduino Serial
     *
     * @param[in]  format     Variable argument list
     */
    void ei_printf(const char *format, ...)
    {
        static char print_buf[1024] = {0};

        va_list args;
        va_start(args, format);
        int r = vsnprintf(print_buf, sizeof(print_buf), format, args);
        va_end(args);

        if (r > 0)
        {
            Serial.write(print_buf);
        }
    }

    /**
     * @brief Return the sign of the number
     *
     * @param number
     * @return int 1 if positive (or 0) -1 if negative
     */
    float ei_get_sign(float number)
    {
        return (number >= 0.0) ? 1.0 : -1.0;
    }

    /**
     * @brief      Run inferencing in the background.
     */
    void run_inference_background()
    {
        // wait until we have a full buffer

        //          10 * 200 + 100 = 2100
        // + 100 je blo
        delay((EI_CLASSIFIER_INTERVAL_MS * EI_CLASSIFIER_RAW_SAMPLE_COUNT) + 100);

        // This is a structure that smoothens the output result
        // With the default settings 70% of readings should be the same before classifying.
        ei_classifier_smooth_t smooth;
        ei_classifier_smooth_init(&smooth, 10 /* no. of readings */, 7 /* min. readings the same */, 0.8 /* min. confidence */, 0.3 /* max anomaly */);

        while (1)
        {
            // copy the buffer
            memcpy(inference_buffer, buffer, EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE * sizeof(float));

            // Turn the raw buffer in a signal which we can the classify
            signal_t signal;
            int err = numpy::signal_from_buffer(inference_buffer, EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, &signal);
            if (err != 0)
            {
                ei_printf("Failed to create signal from buffer (%d)\n", err);
                return;
            }

            // Run the classifier
            ei_impulse_result_t result = {0};

            err = run_classifier(&signal, &result, debug_nn);
            if (err != EI_IMPULSE_OK)
            {
                ei_printf("ERR: Failed to run classifier (%d)\n", err);
                return;
            }

            // print the predictions
            /*
            ei_printf("Predictions ");
            ei_printf("(DSP: %d ms., Classification: %d ms., Anomaly: %d ms.)",
                      result.timing.dsp, result.timing.classification, result.timing.anomaly);
            ei_printf(": ");
            */

            // ei_classifier_smooth_update yields the predicted label
            const char *prediction = ei_classifier_smooth_update(&smooth, &result);
            predictionStr = prediction;
            ei_printf("%s ", prediction);
            // print the cumulative results
            ei_printf(" [ ");
            for (size_t ix = 0; ix < smooth.count_size; ix++)
            {
                ei_printf("%u", smooth.count[ix]);
                if (ix != smooth.count_size + 1)
                {
                    ei_printf(", ");
                }
                else
                {
                    ei_printf(" ");
                }
            }
            ei_printf("]\n");

            if (central.connected())
            {
                if (predictionStr != lastPredictionStr)
                {
                    ei_printf("Sending data to central\n");
                    if (strcmp(prediction, "hoja") == 0)
                    {
                        ei_printf("Prediction: hoja \n");
                        copatiHoja.writeValue(1);
                        copatiStopnice.writeValue(0);
                        copatiTek.writeValue(0);
                        copatiIdle.writeValue(0);
                        copatiUncertain.writeValue(0);
                    }
                    else if (strcmp(prediction, "stopnice") == 0)
                    {
                        ei_printf("Prediction: stopnice \n");
                        copatiStopnice.writeValue(1);
                        copatiHoja.writeValue(0);
                        copatiTek.writeValue(0);
                        copatiIdle.writeValue(0);
                        copatiUncertain.writeValue(0);
                    }
                    else if (strcmp(prediction, "tek") == 0)
                    {
                        ei_printf("Prediction: tek \n");
                        copatiTek.writeValue(1);
                        copatiHoja.writeValue(0);
                        copatiStopnice.writeValue(0);
                        copatiIdle.writeValue(0);
                        copatiUncertain.writeValue(0);
                    }
                    else if (strcmp(prediction, "idle") == 0)
                    {
                        ei_printf("Prediction: idle \n");
                        copatiIdle.writeValue(1);
                        copatiHoja.writeValue(0);
                        copatiStopnice.writeValue(0);
                        copatiTek.writeValue(0);
                        copatiUncertain.writeValue(0);
                    }
                    else if (strcmp(prediction, "uncertain") == 0)
                    {
                        ei_printf("Prediction: uncertain \n");
                        copatiUncertain.writeValue(1);
                        copatiHoja.writeValue(0);
                        copatiStopnice.writeValue(0);
                        copatiTek.writeValue(0);
                        copatiIdle.writeValue(0);
                    }
                    lastPredictionStr = predictionStr;
                }
            }
            delay(run_inference_every_ms);
        }
        ei_classifier_smooth_free(&smooth);
    }

    /**
     * @brief      Get data and run inferencing
     *
     * @param[in]  debug  Get debug info if true
     */
    void loop()
    {
        while (1)
        {
            if (!BLE.central())
            {
                connect_BLE();
            }
            // Determine the next tick (and then sleep later)
            uint64_t next_tick = micros() + (EI_CLASSIFIER_INTERVAL_MS * 1000);
            //printf("%" PRIu64 "\n", next_tick);


            // roll the buffer -3 points so we can overwrite the last one
            numpy::roll(buffer, EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE, -3);

            // read to the end of the buffer
            IMU.readAcceleration(
                buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 3],
                buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 2],
                buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 1]);
            for (int i = 0; i < 3; i++)
            {
                if (fabs(buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 3 + i]) > MAX_ACCEPTED_RANGE)
                {
                    buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 3 + i] = ei_get_sign(buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 3 + i]) * MAX_ACCEPTED_RANGE;
                }
            }

            buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 3] *= CONVERT_G_TO_MS2;
            buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 2] *= CONVERT_G_TO_MS2;
            buffer[EI_CLASSIFIER_DSP_INPUT_FRAME_SIZE - 1] *= CONVERT_G_TO_MS2;

            // and wait for next tick
            uint64_t time_to_wait = next_tick - micros();
            int abc = (int)floor((float)time_to_wait / 1000.0f);
            if (abc > 100){
                abc = 100;
            }
            delay(abc);
            delayMicroseconds(time_to_wait % 1000);
        }
    }

    #if !defined(EI_CLASSIFIER_SENSOR) || EI_CLASSIFIER_SENSOR != EI_CLASSIFIER_SENSOR_ACCELEROMETER
    #error "Invalid model for current sensor"
    #endif

    void blink(long interval, int ledPin)
    {
        unsigned long currentMillis = millis();
        if (currentMillis - previousMillis >= interval)
        {
            previousMillis = currentMillis;
            if (ledState == LOW){
                ledState = HIGH;
                }
            else{
                ledState = LOW;
                }
            digitalWrite(ledPin, ledState);
        }
    }

    void connect_BLE()
    {
        digitalWrite(RED, LOW);
        digitalWrite(GREEN, HIGH);
        while (1)
        {
            blink(400, RED);
            // Serial.println("Not connected...");
            central = BLE.central();
            if (central)
            {
                digitalWrite(RED, HIGH);
                digitalWrite(BLUE, HIGH);
                digitalWrite(GREEN, LOW);
                digitalWrite(LED_PWR, HIGH);

                Serial.print("Connected to central: ");
                Serial.println(central.address());

                break;
            }
        }
    }
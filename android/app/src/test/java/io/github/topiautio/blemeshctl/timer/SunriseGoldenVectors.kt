package io.github.topiautio.blemeshctl.timer

/** Exact outputs of Python `blemeshctl.timers.sunrise_program_text`; generated, do not edit by hand. */
object SunriseGoldenVectors {
    /** Full program text for short ramps. */
    val texts: Map<Int, String> = mapOf(
        60 to "# sunrise 1m\n" +
            "color ff0000 1\n" +
            "wait 5s\n" +
            "color ff1100 6\n" +
            "wait 5s\n" +
            "color ff2100 12\n" +
            "wait 5s\n" +
            "color ff3200 17\n" +
            "wait 5s\n" +
            "color ff4302 24\n" +
            "wait 5s\n" +
            "color ff5408 34\n" +
            "wait 5s\n" +
            "color ff650d 43\n" +
            "wait 5s\n" +
            "color ff7713 53\n" +
            "wait 5s\n" +
            "color ff8622 63\n" +
            "wait 5s\n" +
            "color ff9433 73\n" +
            "wait 5s\n" +
            "color ffa345 83\n" +
            "wait 5s\n" +
            "color ffaa53 92\n" +
            "wait 5s\n" +
            "color ffb060 100\n",
        120 to "# sunrise 2m\n" +
            "color ff0000 1\n" +
            "wait 5s\n" +
            "color ff0800 4\n" +
            "wait 5s\n" +
            "color ff1100 6\n" +
            "wait 5s\n" +
            "color ff1900 9\n" +
            "wait 5s\n" +
            "color ff2100 12\n" +
            "wait 5s\n" +
            "color ff2a00 14\n" +
            "wait 5s\n" +
            "color ff3200 17\n" +
            "wait 5s\n" +
            "color ff3a00 19\n" +
            "wait 5s\n" +
            "color ff4302 24\n" +
            "wait 5s\n" +
            "color ff4c05 29\n" +
            "wait 5s\n" +
            "color ff5408 34\n" +
            "wait 5s\n" +
            "color ff5d0b 38\n" +
            "wait 5s\n" +
            "color ff650d 43\n" +
            "wait 5s\n" +
            "color ff6e10 48\n" +
            "wait 5s\n" +
            "color ff7713 53\n" +
            "wait 5s\n" +
            "color ff7e19 58\n" +
            "wait 5s\n" +
            "color ff8622 63\n" +
            "wait 5s\n" +
            "color ff8d2b 68\n" +
            "wait 5s\n" +
            "color ff9433 73\n" +
            "wait 5s\n" +
            "color ff9c3c 78\n" +
            "wait 5s\n" +
            "color ffa345 83\n" +
            "wait 5s\n" +
            "color ffa84c 88\n" +
            "wait 5s\n" +
            "color ffaa53 92\n" +
            "wait 5s\n" +
            "color ffad59 96\n" +
            "wait 5s\n" +
            "color ffb060 100\n",
        300 to "# sunrise 5m\n" +
            "color ff0000 1\n" +
            "wait 5s\n" +
            "color ff0300 2\n" +
            "wait 5s\n" +
            "color ff0700 3\n" +
            "wait 5s\n" +
            "color ff0a00 4\n" +
            "wait 5s\n" +
            "color ff0d00 5\n" +
            "wait 5s\n" +
            "color ff1100 6\n" +
            "wait 5s\n" +
            "color ff1400 7\n" +
            "wait 5s\n" +
            "color ff1700 8\n" +
            "wait 5s\n" +
            "color ff1b00 9\n" +
            "wait 5s\n" +
            "color ff1e00 11\n" +
            "wait 5s\n" +
            "color ff2100 12\n" +
            "wait 5s\n" +
            "color ff2500 13\n" +
            "wait 5s\n" +
            "color ff2800 14\n" +
            "wait 5s\n" +
            "color ff2b00 15\n" +
            "wait 5s\n" +
            "color ff2f00 16\n" +
            "wait 5s\n" +
            "color ff3200 17\n" +
            "wait 5s\n" +
            "color ff3500 18\n" +
            "wait 5s\n" +
            "color ff3900 19\n" +
            "wait 5s\n" +
            "color ff3c00 20\n" +
            "wait 5s\n" +
            "color ff3f01 22\n" +
            "wait 5s\n" +
            "color ff4302 24\n" +
            "wait 5s\n" +
            "color ff4603 26\n" +
            "wait 5s\n" +
            "color ff4a04 28\n" +
            "wait 5s\n" +
            "color ff4d06 30\n" +
            "wait 5s\n" +
            "color ff5107 32\n" +
            "wait 5s\n" +
            "color ff5408 34\n" +
            "wait 5s\n" +
            "color ff5809 36\n" +
            "wait 5s\n" +
            "color ff5b0a 38\n" +
            "wait 5s\n" +
            "color ff5e0b 39\n" +
            "wait 5s\n" +
            "color ff620c 41\n" +
            "wait 5s\n" +
            "color ff650d 43\n" +
            "wait 5s\n" +
            "color ff690e 45\n" +
            "wait 5s\n" +
            "color ff6c10 47\n" +
            "wait 5s\n" +
            "color ff7011 49\n" +
            "wait 5s\n" +
            "color ff7312 51\n" +
            "wait 5s\n" +
            "color ff7713 53\n" +
            "wait 5s\n" +
            "color ff7a14 55\n" +
            "wait 5s\n" +
            "color ff7d17 57\n" +
            "wait 5s\n" +
            "color ff801b 59\n" +
            "wait 5s\n" +
            "color ff831e 61\n" +
            "wait 5s\n" +
            "color ff8622 63\n" +
            "wait 5s\n" +
            "color ff8925 65\n" +
            "wait 5s\n" +
            "color ff8c29 67\n" +
            "wait 5s\n" +
            "color ff8f2c 69\n" +
            "wait 5s\n" +
            "color ff9130 71\n" +
            "wait 5s\n" +
            "color ff9433 73\n" +
            "wait 5s\n" +
            "color ff9737 75\n" +
            "wait 5s\n" +
            "color ff9a3a 77\n" +
            "wait 5s\n" +
            "color ff9d3e 79\n" +
            "wait 5s\n" +
            "color ffa041 81\n" +
            "wait 5s\n" +
            "color ffa345 83\n" +
            "wait 5s\n" +
            "color ffa648 85\n" +
            "wait 5s\n" +
            "color ffa74b 87\n" +
            "wait 5s\n" +
            "color ffa84d 88\n" +
            "wait 5s\n" +
            "color ffa950 90\n" +
            "wait 5s\n" +
            "color ffaa53 92\n" +
            "wait 5s\n" +
            "color ffac55 93\n" +
            "wait 5s\n" +
            "color ffad58 95\n" +
            "wait 5s\n" +
            "color ffae5b 97\n" +
            "wait 5s\n" +
            "color ffaf5d 98\n" +
            "wait 5s\n" +
            "color ffb060 100\n",
    )

    /** SHA-256 of the program text for other durations. */
    val hashes: Map<Int, String> = mapOf(
        61 to "eaa93d4ccc20abd5479bfb0b9ee7cf9d30d93278cfd31d3c2fcd149656224fba",
        75 to "87464acc0afbd78740b59b0e90b8f640c528709836f9f88cbbadbf8f60d8c222",
        90 to "26dab1b09d13f7081780eaa493f4d6ec8e74a28b2eed883692e61ac31176bab8",
        299 to "b0eb240f196f7d6c458e9b6e28de0ffedc16cc9ff96efff6b3744e97486068f0",
        300 to "744238933e0a2950ae3a4879845771e222077d7b0d3413d983ba73e030935b9e",
        301 to "4f5465effec2552460eae566cbd8be61c93ac56b0ca1424e2842d3233138bed9",
        599 to "879013b506d9846cd94dc923feee5a21954ed0dd69db414d022cd038d444f6b1",
        600 to "7dae35fd05f5edcb2886bc681635d475615ed58b8113ea50b481268ed2e3aa5e",
        900 to "57da5fda7ea3fa04e777205f0e2a039a62a153094da6e68afbdd10b174bd2b2a",
        1234 to "de081490598b411f87b401c19b8e45e91612ce34e948b9227691283a1d1e3cfe",
        1799 to "8794e5e2bbac7e9f5f0542d7561255f0f197774a0fae5030677fd495bd9f9afe",
        1800 to "c946cc287c73d6f2c8ea78b585821cbe15bffe08a8266b6bcb50ceab8ac6eb71",
        1801 to "85cccacdfe9ca5d82d9123366d4d1518cd2a4825b012c967aedef48028a8f6a0",
        3600 to "4c9e06480ad7d9cd9c9b2f0ca39861434db8c13eb9aa54a05f9f334f4fd608f9",
        5400 to "e29be068166bb7452fb9c885d1c664e6487f0ab9e594977b0e720ab22cae2efb",
        7200 to "747a7bb22161b50f5e7293250b5d0590d3c43fd8134d49455dd307c822b40fdf",
        10000 to "660efd3b652b62e48bf76fbceb395d26d98bd2cda8acac987ea86e355ef972cb",
        12345 to "ee9c5711746de96bed33fa92c98523d4ff71399f145d6a55e49d1128ea546768",
        21599 to "1daf501219798c60030b0dc72791cf61b3d1e2984036ac4bd18371aaf0c43127",
        21600 to "4ec6370146d5eab09a131c4c7240031dfcf7230f494ecb74bcd43690d6f57e43",
    )
}

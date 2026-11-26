# Reminder dependencies

Downloaded from https://repo.maven.apache.org/maven2/. Builds use `lib/*`. License notices are included in each jar's META-INF directory.

SMTP uses Eclipse Angus Mail and Jakarta Activation. Web push uses webpush-java, Bouncy Castle and jose4j for encryption and VAPID signing. Prepared requests are sent with Java HttpClient and a timeout. Apache HTTP jars support the request representation. The library's optional Netty async transport and CLI are not used.

| Maven group | Artifact |
| --- | --- |
| org.eclipse.angus | jakarta.mail, angus-activation |
| jakarta.activation | jakarta.activation-api |
| nl.martijndwars | web-push |
| org.bouncycastle | bcprov-jdk18on |
| org.bitbucket.b_c | jose4j |
| org.apache.httpcomponents | httpclient, httpcore, httpcore-nio, httpasyncclient |
| commons-codec | commons-codec |
| commons-logging | commons-logging |
| org.slf4j | slf4j-api, slf4j-nop |

Versions and SHA-256 hashes (existing JSON/PDF jars included for reproducibility):

| Jar | SHA-256 |
| --- | --- |
| angus-activation-2.0.3.jar | a6bd35c538cf90fff941ad6258c40c08fca0b5c9c3f536c657114f27ce0527a7 |
| bcprov-jdk18on-1.83.jar | 82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb |
| commons-codec-1.20.0.jar | 6af66595f9f6a7bb58ce66518d6888d40b547c366d2262f06676eee19528ff66 |
| commons-logging-1.3.5.jar | 6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4 |
| gson-2.13.2.jar | dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0 |
| httpasyncclient-4.1.5.jar | 0c1877489a9d1ba4fa50f6cfcab11d1123618858cb31d56afaab5afdd5064d99 |
| httpclient-4.5.14.jar | c8bc7e1c51a6d4ce72f40d2ebbabf1c4b68bfe76e732104b04381b493478e9d6 |
| httpcore-4.4.16.jar | 6c9b3dd142a09dc468e23ad39aad6f75a0f2b85125104469f026e52a474e464f |
| httpcore-nio-4.4.16.jar | 4018736ede2d321034e8517ea90baefb31831a8608afccc446d8a699fb1d00d4 |
| jakarta.activation-api-2.1.3.jar | 01b176d718a169263e78290691fc479977186bcc6b333487325084d6586f4627 |
| jakarta.mail-2.0.5.jar | f48001755a4a591bb1b602965337fcf316d794c43783052f88ede05130539358 |
| jose4j-0.9.6.jar | 7314af50cde9c99e8eaf43eee617a23edcc6bb43036221064355094999d837ef |
| slf4j-api-2.0.17.jar | 7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832 |
| slf4j-nop-2.0.17.jar | 3716f83649ec66161a2edefd4f49df34d1dd1c51cdcf941996c6987260f0a829 |
| web-push-5.1.2.jar | fa3bfbc19d783f8542df6e05ae280a2a8cbfcd49d51491a2326a8f35428f579e |


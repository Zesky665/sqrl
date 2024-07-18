| Function Documentation |
|-------------------------|
| `AtZone (timestamp with local time zone, string) → timestamp with local time zone`<br><br> Convert a timestamp to a given time zone.<br> Example: `AtZone('2021-01-01T00:00:00Z'::timestamptz, 'Europe/London') → 2021-01-01T00:00:00+00:00` |
| `EndOfDay (timestamp with local time zone [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the day for a given timestamp. Optional parameters: `multiple`, to specify multiple amounts, and `offset`, to specify the offset. Usage of these parameters adjusts the time calculations accordingly.<br> Example: `EndOfDay('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-01T23:59:59.999999999Z` |
| `EndOfHour (timestamp with local time zone, [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the hour for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfHour('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-01T00:59:59.999999999Z` |
| `EndOfMinute (timestamp with local time zone [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the minute for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfMinute('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-01T00:00:59.999999999Z` |
| `EndOfMonth (timestamp with local time zone, [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the month for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfMonth('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-31T23:59:59.999999999Z` |
| `EndOfSecond (timestamp with local time zone [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the second for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfSecond('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-01T00:00:00.999999999Z` |
| `EndOfWeek (timestamp with local time zone [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the week for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfWeek('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-01-03T23:59:59.999999999Z` |
| `EndOfYear (timestamp with local time zone [, bigint, bigint]) → timestamp with local time zone`<br><br> Get the end of the year for a given timestamp. Optional parameters: `multiple` and `offset` adjust the time calculations accordingly.<br> Example: `EndOfYear('2021-01-01T00:00:00Z'::timestamptz, 1, 0) → 2021-12-31T23:59:59.999999999Z` |
| `EpochMilliToTimestamp (bigint) → timestamp with local time zone`<br><br> Convert epoch milliseconds to a timestamp.<br> Example: `EpochMilliToTimestamp(1610000000000::bigint) → 2021-01-07T06:13:20Z` |
| `EpochToTimestamp (bigint) → timestamp with local time zone`<br><br> Convert epoch seconds to a timestamp.<br> Example: `EpochToTimestamp(1610000000::bigint) → 2021-01-07T06:13:20Z` |
| `ParseTimestamp (string [, string]) → timestamp with local time zone`<br><br> Parse a string to a timestamp using an optional format. If no format is specified, the ISO-8601 format is used by default.<br> Example: `ParseTimestamp('2021-01-01T00:00:00Z', 'yyyy-MM-dd''T''HH:mm:ssXXX') → 2021-01-01T00:00:00Z` |
| `TimestampToEpoch (timestamp with local time zone) → bigint`<br><br> Convert a timestamp to epoch seconds.<br> Example: `TimestampToEpoch('2021-01-01T00:00:00Z'::timestamptz) → 1609459200` |
| `TimestampToEpochMilli (timestamp with local time zone) → bigint`<br><br> Convert a timestamp to epoch milliseconds.<br> Example: `TimestampToEpochMilli('2021-01-01T00:00:00Z'::timestamptz) → 1609459200000` |
| `TimestampToString (timestamp with local time zone) → string`<br><br> Convert a timestamp to a string representation.<br> Example: `TimestampToString('2021-01-01T00:00:00Z'::timestamptz) → '2021-01-01T00:00:00Z'` |
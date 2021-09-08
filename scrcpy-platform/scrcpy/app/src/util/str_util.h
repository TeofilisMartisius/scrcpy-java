#ifndef STRUTIL_H
#define STRUTIL_H

#include "common.h"

#include <stdbool.h>
#include <stddef.h>

// like strncpy, except:
//  - it copies at most n-1 chars
//  - the dest string is nul-terminated
//  - it does not write useless bytes if strlen(src) < n
//  - it returns the number of chars actually written (max n-1) if src has
//    been copied completely, or n if src has been truncated
size_t
xstrncpy(char *dest, const char *src, size_t n);

// join tokens by sep into dst
// returns the number of chars actually written (max n-1) if no truncation
// occurred, or n if truncated
size_t
xstrjoin(char *dst, const char *const tokens[], char sep, size_t n);

// quote a string
// returns the new allocated string, to be freed by the caller
char *
strquote(const char *src);

// parse s as an integer into value
// returns true if the conversion succeeded, false otherwise
bool
parse_integer(const char *s, long *out);

// parse s as integers separated by sep (for example '1234:2000')
// returns the number of integers on success, 0 on failure
size_t
parse_integers(const char *s, const char sep, size_t max_items, long *out);

// parse s as an integer into value
// like parse_integer(), but accept 'k'/'K' (x1000) and 'm'/'M' (x1000000) as
// suffix
// returns true if the conversion succeeded, false otherwise
bool
parse_integer_with_suffix(const char *s, long *out);

// search s in the list separated by sep
// for example, strlist_contains("a,bc,def", ',', "bc") returns true
bool
strlist_contains(const char *list, char sep, const char *s);

// return the index to truncate a UTF-8 string at a valid position
size_t
utf8_truncation_index(const char *utf8, size_t max_len);

#ifdef _WIN32
// convert a UTF-8 string to a wchar_t string
// returns the new allocated string, to be freed by the caller
wchar_t *
utf8_to_wide_char(const char *utf8);

char *
utf8_from_wide_char(const wchar_t *s);
#endif

#endif

/*      $NetBSD: tree.h,v 1.8 2004/03/28 19:38:30 provos Exp $  */
/*      $OpenBSD: tree.h,v 1.7 2002/10/17 21:51:54 art Exp $    */
/* $FreeBSD: stable/9/sys/sys/tree.h 189204 2009-03-01 04:57:23Z bms $ */

#ifndef TREE_H
#define TREE_H

#ifndef __unused
#define __unused __attribute__((__unused__))
#endif

#define RB_HEAD(name, type)                                             \
struct name {                                                           \
        struct type *rbh_root; /* root of the tree */                   \
}

#define RB_INITIALIZER(root)                                            \
        { NULL }

#define RB_INIT(root) do {                                              \
        (root)->rbh_root = NULL;                                        \
} while (0)

#define RB_BLACK        0
#define RB_RED          1
#define RB_ENTRY(type)                                                  \
struct {                                                                \
        struct type *rbe_left;          /* left element */              \
        struct type *rbe_right;         /* right element */             \
        struct type *rbe_parent;        /* parent element */            \
        int rbe_color;                  /* node color */                \
}

#define RB_LEFT(elm, field)             (elm)->field.rbe_left
#define RB_RIGHT(elm, field)            (elm)->field.rbe_right
#define RB_PARENT(elm, field)           (elm)->field.rbe_parent
#define RB_COLOR(elm, field)            (elm)->field.rbe_color
#define RB_ROOT(head)                   (head)->rbh_root
#define RB_EMPTY(head)                  (RB_ROOT(head) == NULL)

#define RB_SET(elm, parent, field) do {                                 \
        RB_PARENT(elm, field) = parent;                                 \
        RB_LEFT(elm, field) = RB_RIGHT(elm, field) = NULL;              \
        RB_COLOR(elm, field) = RB_RED;                                  \
} while (0)

#define RB_ROTATE_LEFT(head, elm, tmp, field) do {                      \
        (tmp) = RB_RIGHT(elm, field);                                   \
        if ((RB_RIGHT(elm, field) = RB_LEFT(tmp, field)) != NULL) {     \
                RB_PARENT(RB_LEFT(tmp, field), field) = (elm);          \
        }                                                               \
        if ((RB_PARENT(tmp, field) = RB_PARENT(elm, field)) != NULL) {  \
                if ((elm) == RB_LEFT(RB_PARENT(elm, field), field))     \
                        RB_LEFT(RB_PARENT(elm, field), field) = (tmp);  \
                else                                                    \
                        RB_RIGHT(RB_PARENT(elm, field), field) = (tmp); \
        } else                                                          \
                (head)->rbh_root = (tmp);                               \
        RB_LEFT(tmp, field) = (elm);                                    \
        RB_PARENT(elm, field) = (tmp);                                  \
} while (0)

#define RB_ROTATE_RIGHT(head, elm, tmp, field) do {                     \
        (tmp) = RB_LEFT(elm, field);                                    \
        if ((RB_LEFT(elm, field) = RB_RIGHT(tmp, field)) != NULL) {     \
                RB_PARENT(RB_RIGHT(tmp, field), field) = (elm);         \
        }                                                               \
        if ((RB_PARENT(tmp, field) = RB_PARENT(elm, field)) != NULL) {  \
                if ((elm) == RB_LEFT(RB_PARENT(elm, field), field))     \
                        RB_LEFT(RB_PARENT(elm, field), field) = (tmp);  \
                else                                                    \
                        RB_RIGHT(RB_PARENT(elm, field), field) = (tmp); \
        } else                                                          \
                (head)->rbh_root = (tmp);                               \
        RB_RIGHT(tmp, field) = (elm);                                   \
        RB_PARENT(elm, field) = (tmp);                                  \
} while (0)

#define RB_PROTOTYPE_STATIC(name, type, field, cmp)                     \
        RB_PROTOTYPE_INTERNAL(name, type, field, cmp, __unused static)

#define RB_PROTOTYPE_INTERNAL(name, type, field, cmp, attr)             \
attr struct type *name##_RB_INSERT(struct name *, struct type *);       \
attr struct type *name##_RB_FIND(struct name *, struct type *);         \
attr struct type *name##_RB_NEXT(struct type *);                        \
attr struct type *name##_RB_REMOVE(struct name *, struct type *);       \
attr struct type *name##_RB_MINMAX(struct name *, int);

#define RB_GENERATE_STATIC(name, type, field, cmp)                      \
        RB_GENERATE_INTERNAL(name, type, field, cmp, __unused static)

#define RB_GENERATE_INTERNAL(name, type, field, cmp, attr)              \
attr struct type *                                                      \
name##_RB_INSERT(struct name *head, struct type *elm)                   \
{                                                                       \
    struct type *tmp;                                                   \
    struct type *parent = NULL;                                         \
    int comp = 0;                                                       \
    tmp = RB_ROOT(head);                                                \
    while (tmp) {                                                       \
        parent = tmp;                                                   \
        comp = (cmp)(elm, parent);                                      \
        if (comp < 0)                                                   \
            tmp = RB_LEFT(tmp, field);                                  \
        else if (comp > 0)                                              \
            tmp = RB_RIGHT(tmp, field);                                 \
        else                                                            \
            return (tmp);                                               \
    }                                                                   \
    RB_SET(elm, parent, field);                                         \
    if (parent != NULL) {                                               \
        if (comp < 0)                                                   \
            RB_LEFT(parent, field) = elm;                               \
        else                                                            \
            RB_RIGHT(parent, field) = elm;                              \
    } else                                                              \
        RB_ROOT(head) = elm;                                            \
    return (NULL);                                                      \
}                                                                       \
attr struct type *                                                      \
name##_RB_FIND(struct name *head, struct type *elm)                     \
{                                                                       \
    struct type *tmp = RB_ROOT(head);                                   \
    int comp;                                                           \
    while (tmp) {                                                       \
        comp = cmp(elm, tmp);                                           \
        if (comp < 0)                                                   \
            tmp = RB_LEFT(tmp, field);                                  \
        else if (comp > 0)                                              \
            tmp = RB_RIGHT(tmp, field);                                 \
        else                                                            \
            return (tmp);                                               \
    }                                                                   \
    return (NULL);                                                      \
}                                                                       \
attr struct type *                                                      \
name##_RB_NEXT(struct type *elm)                                        \
{                                                                       \
    if (RB_RIGHT(elm, field)) {                                         \
        elm = RB_RIGHT(elm, field);                                     \
        while (RB_LEFT(elm, field))                                     \
            elm = RB_LEFT(elm, field);                                  \
        return (elm);                                                   \
    } else {                                                            \
        int comp;                                                       \
        while (RB_PARENT(elm, field) &&                                 \
            (elm == RB_RIGHT(RB_PARENT(elm, field), field)))            \
            elm = RB_PARENT(elm, field);                                \
        return (RB_PARENT(elm, field));                                 \
    }                                                                   \
}                                                                       \
attr struct type *                                                      \
name##_RB_REMOVE(struct name *head, struct type *elm)                   \
{                                                                       \
    struct type *child, *parent, *old = elm;                            \
    if (RB_LEFT(elm, field) == NULL)                                    \
        child = RB_RIGHT(elm, field);                                   \
    else if (RB_RIGHT(elm, field) == NULL)                              \
        child = RB_LEFT(elm, field);                                    \
    else {                                                              \
        struct type *left;                                              \
        elm = RB_RIGHT(elm, field);                                     \
        while ((left = RB_LEFT(elm, field)) != NULL)                    \
            elm = left;                                                 \
        child = RB_RIGHT(elm, field);                                   \
        parent = RB_PARENT(elm, field);                                 \
        if (child)                                                      \
                RB_PARENT(child, field) = parent;                       \
        if (parent) {                                                   \
                if (RB_LEFT(parent, field) == elm)                      \
                        RB_LEFT(parent, field) = child;                 \
                else                                                    \
                        RB_RIGHT(parent, field) = child;                \
        } else                                                          \
                RB_ROOT(head) = child;                                  \
        if (RB_PARENT(elm, field) == old)                               \
                parent = elm;                                           \
        (elm)->field = (old)->field;                                    \
        if (RB_PARENT(old, field)) {                                    \
                if (RB_LEFT(RB_PARENT(old, field), field) == old)       \
                        RB_LEFT(RB_PARENT(old, field), field) = elm;    \
                else                                                    \
                        RB_RIGHT(RB_PARENT(old, field), field) = elm;   \
        } else                                                          \
                RB_ROOT(head) = elm;                                    \
        RB_PARENT(RB_LEFT(old, field), field) = elm;                    \
        if (RB_RIGHT(old, field))                                       \
                RB_PARENT(RB_RIGHT(old, field), field) = elm;           \
        return (old);                                                   \
    }                                                                   \
    parent = RB_PARENT(elm, field);                                     \
    if (child)                                                          \
            RB_PARENT(child, field) = parent;                           \
    if (parent) {                                                       \
            if (RB_LEFT(parent, field) == elm)                          \
                    RB_LEFT(parent, field) = child;                     \
            else                                                        \
                    RB_RIGHT(parent, field) = child;                    \
    } else                                                              \
            RB_ROOT(head) = child;                                      \
    return (old);                                                       \
}                                                                       \
attr struct type *                                                      \
name##_RB_MINMAX(struct name *head, int val)                            \
{                                                                       \
    struct type *tmp = RB_ROOT(head);                                   \
    struct type *parent = NULL;                                         \
    while (tmp) {                                                       \
        parent = tmp;                                                   \
        if (val < 0)                                                    \
            tmp = RB_LEFT(tmp, field);                                  \
        else                                                            \
            tmp = RB_RIGHT(tmp, field);                                 \
    }                                                                   \
    return (parent);                                                    \
}

#define RB_FIND(name, x, y)     name##_RB_FIND(x, y)
#define RB_INSERT(name, x, y)   name##_RB_INSERT(x, y)
#define RB_REMOVE(name, x, y)   name##_RB_REMOVE(x, y)
#define RB_FOREACH_SAFE(x, name, head, tvar)                             \
    for ((x) = RB_MIN(name, head);                                      \
         (x) && ((tvar) = name##_RB_NEXT(x), 1);                        \
         (x) = (tvar))

#define RB_MIN(name, x)         name##_RB_MINMAX(x, -1)

#endif

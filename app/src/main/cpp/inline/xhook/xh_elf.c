// Copyright (c) 2018-present, iQIYI, Inc. All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//

// Created by caikelun on 2018-04-11.

#include <unistd.h>
#include <stdint.h>
#include <inttypes.h>
#include <elf.h>
#include <link.h>
#include <string.h>
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include "xh_errno.h"
#include "xh_log.h"
#include "xh_util.h"
#include "xh_elf.h"

#define XH_ELF_DEBUG 0

#ifndef EI_ABIVERSION
#define EI_ABIVERSION 8
#endif

#if defined(__arm__)
#define XH_ELF_R_GENERIC_JUMP_SLOT R_ARM_JUMP_SLOT
#define XH_ELF_R_GENERIC_GLOB_DAT  R_ARM_GLOB_DAT
#define XH_ELF_R_GENERIC_ABS       R_ARM_ABS32
#elif defined(__aarch64__)
#define XH_ELF_R_GENERIC_JUMP_SLOT R_AARCH64_JUMP_SLOT
#define XH_ELF_R_GENERIC_GLOB_DAT  R_AARCH64_GLOB_DAT
#define XH_ELF_R_GENERIC_ABS       R_AARCH64_ABS64
#elif defined(__i386__)
#define XH_ELF_R_GENERIC_JUMP_SLOT R_386_JMP_SLOT
#define XH_ELF_R_GENERIC_GLOB_DAT  R_386_GLOB_DAT
#define XH_ELF_R_GENERIC_ABS       R_386_32
#elif defined(__x86_64__)
#define XH_ELF_R_GENERIC_JUMP_SLOT R_X86_64_JUMP_SLOT
#define XH_ELF_R_GENERIC_GLOB_DAT  R_X86_64_GLOB_DAT
#define XH_ELF_R_GENERIC_ABS       R_X86_64_64
#endif

#if defined(__LP64__)
#define XH_ELF_R_SYM(info)  ELF64_R_SYM(info)
#define XH_ELF_R_TYPE(info) ELF64_R_TYPE(info)
#else
#define XH_ELF_R_SYM(info)  ELF32_R_SYM(info)
#define XH_ELF_R_TYPE(info) ELF32_R_TYPE(info)
#endif

typedef struct
{
    uint8_t  *cur;
    uint8_t  *end;
    int       is_use_rela;
} xh_elf_plain_reloc_iterator_t;

static void xh_elf_plain_reloc_iterator_init(xh_elf_plain_reloc_iterator_t *self,
                                             ElfW(Addr) rel, ElfW(Word) rel_sz, int is_use_rela)
{
    self->cur = (uint8_t *)rel;
    self->end = self->cur + rel_sz;
    self->is_use_rela = is_use_rela;
}

static void *xh_elf_plain_reloc_iterator_next(xh_elf_plain_reloc_iterator_t *self)
{
    if(self->cur >= self->end) return NULL;
    void *ret = (void *)(self->cur);
    self->cur += (self->is_use_rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel)));
    return ret;
}

typedef struct
{
    uint8_t  *cur;
    uint8_t  *end;
} xh_elf_sleb128_decoder_t;

static void xh_elf_sleb128_decoder_init(xh_elf_sleb128_decoder_t *self,
                                        ElfW(Addr) rel, ElfW(Word) rel_sz)
{
    self->cur = (uint8_t *)rel;
    self->end = self->cur + rel_sz;
}

static int xh_elf_sleb128_decoder_next(xh_elf_sleb128_decoder_t *self, size_t *ret)
{
    size_t value = 0;
    static const size_t size = 8 * sizeof(value);
    size_t shift = 0;
    uint8_t byte;
    do
    {
        if(self->cur >= self->end) return XH_ERRNO_FORMAT;
        byte = *(self->cur)++;
        value |= ((size_t)(byte & 127) << shift);
        shift += 7;
    } while(byte & 128);
    if(shift < size && (byte & 64))
        value |= -((size_t)(1) << shift);
    *ret = value;
    return 0;
}

typedef struct
{
    xh_elf_sleb128_decoder_t decoder;
    size_t                   relocation_count;
    size_t                   group_size;
    size_t                   group_flags;
    size_t                   group_r_offset_delta;
    size_t                   relocation_index;
    size_t                   relocation_group_index;
    ElfW(Rela)               rela;
    ElfW(Rel)                rel;
    ElfW(Addr)               r_offset;
    size_t                   r_info;
    ssize_t                  r_addend;
    int                      is_use_rela;
} xh_elf_packed_reloc_iterator_t;

const size_t RELOCATION_GROUPED_BY_INFO_FLAG         = 1;
const size_t RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG = 2;
const size_t RELOCATION_GROUPED_BY_ADDEND_FLAG       = 4;
const size_t RELOCATION_GROUP_HAS_ADDEND_FLAG        = 8;

static int xh_elf_packed_reloc_iterator_init(xh_elf_packed_reloc_iterator_t *self,
                                             ElfW(Addr) rel, ElfW(Word) rel_sz, int is_use_rela)
{
    int r;
    memset(self, 0, sizeof(xh_elf_packed_reloc_iterator_t));
    xh_elf_sleb128_decoder_init(&(self->decoder), rel, rel_sz);
    self->is_use_rela = is_use_rela;
    if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), &(self->relocation_count)))) return r;
    if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), (size_t *)&(self->r_offset)))) return r;
    return 0;
}

static int xh_elf_packed_reloc_iterator_read_group_fields(xh_elf_packed_reloc_iterator_t *self)
{
    int    r;
    size_t val;
    if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), &(self->group_size)))) return r;
    if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), &(self->group_flags)))) return r;
    if(self->group_flags & RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG)
        if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), &(self->group_r_offset_delta)))) return r;
    if(self->group_flags & RELOCATION_GROUPED_BY_INFO_FLAG)
        if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), (size_t *)&(self->r_info)))) return r;
    if((self->group_flags & RELOCATION_GROUP_HAS_ADDEND_FLAG) &&
       (self->group_flags & RELOCATION_GROUPED_BY_ADDEND_FLAG))
    {
        if(0 == self->is_use_rela) { XH_LOG_ERROR("unexpected r_addend in android.rel section"); return XH_ERRNO_FORMAT; }
        if(0 != (r = xh_elf_sleb128_decoder_next(&(self->decoder), &val))) return r;
        self->r_addend += (ssize_t)val;
    }
    else if(0 == (self->group_flags & RELOCATION_GROUP_HAS_ADDEND_FLAG))
        self->r_addend = 0;
    self->relocation_group_index = 0;
    return 0;
}

static void *xh_elf_packed_reloc_iterator_next(xh_elf_packed_reloc_iterator_t *self)
{
    size_t val;
    if(self->relocation_index >= self->relocation_count) return NULL;
    if(self->relocation_group_index == self->group_size)
    {
        if(0 != xh_elf_packed_reloc_iterator_read_group_fields(self)) return NULL;
    }
    if(self->group_flags & RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG)
        self->r_offset += self->group_r_offset_delta;
    else
    {
        if(0 != xh_elf_sleb128_decoder_next(&(self->decoder), &val)) return NULL;
        self->r_offset += val;
    }
    if(0 == (self->group_flags & RELOCATION_GROUPED_BY_INFO_FLAG))
        if(0 != xh_elf_sleb128_decoder_next(&(self->decoder), &(self->r_info))) return NULL;
    if(self->is_use_rela && (self->group_flags & RELOCATION_GROUP_HAS_ADDEND_FLAG) &&
       (0 == (self->group_flags & RELOCATION_GROUPED_BY_ADDEND_FLAG)))
    {
        if(0 != xh_elf_sleb128_decoder_next(&(self->decoder), &val)) return NULL;
        self->r_addend += (ssize_t)val;
    }
    self->relocation_index++;
    self->relocation_group_index++;
    if(self->is_use_rela)
    {
        self->rela.r_offset = self->r_offset;
        self->rela.r_info = self->r_info;
        self->rela.r_addend = self->r_addend;
        return (void *)(&(self->rela));
    }
    else
    {
        self->rel.r_offset = self->r_offset;
        self->rel.r_info = self->r_info;
        return (void *)(&(self->rel));
    }
}

int xh_elf_check_elfheader(uintptr_t base_addr)
{
    ElfW(Ehdr) *ehdr = (ElfW(Ehdr) *)base_addr;
    if(0 != memcmp(ehdr->e_ident, ELFMAG, SELFMAG)) return XH_ERRNO_FORMAT;
#if defined(__LP64__)
    if(ELFCLASS64 != ehdr->e_ident[EI_CLASS]) return XH_ERRNO_FORMAT;
#else
    if(ELFCLASS32 != ehdr->e_ident[EI_CLASS]) return XH_ERRNO_FORMAT;
#endif
    if(ELFDATA2LSB != ehdr->e_ident[EI_DATA]) return XH_ERRNO_FORMAT;
    if(EV_CURRENT != ehdr->e_ident[EI_VERSION]) return XH_ERRNO_FORMAT;
    if(ET_EXEC != ehdr->e_type && ET_DYN != ehdr->e_type) return XH_ERRNO_FORMAT;
#if defined(__arm__)
    if(EM_ARM != ehdr->e_machine) return XH_ERRNO_FORMAT;
#elif defined(__aarch64__)
    if(EM_AARCH64 != ehdr->e_machine) return XH_ERRNO_FORMAT;
#elif defined(__i386__)
    if(EM_386 != ehdr->e_machine) return XH_ERRNO_FORMAT;
#elif defined(__x86_64__)
    if(EM_X86_64 != ehdr->e_machine) return XH_ERRNO_FORMAT;
#else
    return XH_ERRNO_FORMAT;
#endif
    if(EV_CURRENT != ehdr->e_version) return XH_ERRNO_FORMAT;
    return 0;
}

static uint32_t xh_elf_hash(const uint8_t *name)
{
    uint32_t h = 0, g;
    while (*name) { h = (h << 4) + *name++; g = h & 0xf0000000; h ^= g; h ^= g >> 24; }
    return h;
}

static uint32_t xh_elf_gnu_hash(const uint8_t *name)
{
    uint32_t h = 5381;
    while(*name != 0) { h += (h << 5) + *name++; }
    return h;
}

static ElfW(Phdr) *xh_elf_get_first_segment_by_type(xh_elf_t *self, ElfW(Word) type)
{
    ElfW(Phdr) *phdr;
    for(phdr = self->phdr; phdr < self->phdr + self->ehdr->e_phnum; phdr++)
        if(phdr->p_type == type) return phdr;
    return NULL;
}

static ElfW(Phdr) *xh_elf_get_first_segment_by_type_offset(xh_elf_t *self, ElfW(Word) type, ElfW(Off) offset)
{
    ElfW(Phdr) *phdr;
    for(phdr = self->phdr; phdr < self->phdr + self->ehdr->e_phnum; phdr++)
        if(phdr->p_type == type && phdr->p_offset == offset) return phdr;
    return NULL;
}

static int xh_elf_hash_lookup(xh_elf_t *self, const char *symbol, uint32_t *symidx)
{
    uint32_t hash = xh_elf_hash((uint8_t *)symbol);
    uint32_t i;
    for(i = self->bucket[hash % self->bucket_cnt]; 0 != i; i = self->chain[i])
    {
        if(0 == strcmp(symbol, self->strtab + self->symtab[i].st_name))
        { *symidx = i; return 0; }
    }
    return XH_ERRNO_NOTFND;
}

static int xh_elf_gnu_hash_lookup_def(xh_elf_t *self, const char *symbol, uint32_t *symidx)
{
    uint32_t hash = xh_elf_gnu_hash((uint8_t *)symbol);
    static uint32_t elfclass_bits = sizeof(ElfW(Addr)) * 8;
    size_t word = self->bloom[(hash / elfclass_bits) % self->bloom_sz];
    size_t mask = (size_t)1 << (hash % elfclass_bits) | (size_t)1 << ((hash >> self->bloom_shift) % elfclass_bits);
    if((word & mask) != mask) return XH_ERRNO_NOTFND;
    uint32_t i = self->bucket[hash % self->bucket_cnt];
    if(i < self->symoffset) return XH_ERRNO_NOTFND;
    while(1)
    {
        const uint32_t symhash = self->chain[i - self->symoffset];
        if((hash | (uint32_t)1) == (symhash | (uint32_t)1) && 0 == strcmp(symbol, self->strtab + self->symtab[i].st_name))
        { *symidx = i; return 0; }
        if(symhash & (uint32_t)1) break;
        i++;
    }
    return XH_ERRNO_NOTFND;
}

static int xh_elf_gnu_hash_lookup_undef(xh_elf_t *self, const char *symbol, uint32_t *symidx)
{
    for(uint32_t i = 0; i < self->symoffset; i++)
    {
        if(0 == strcmp(symbol, self->strtab + self->symtab[i].st_name))
        { *symidx = i; return 0; }
    }
    return XH_ERRNO_NOTFND;
}

static int xh_elf_gnu_hash_lookup(xh_elf_t *self, const char *symbol, uint32_t *symidx)
{
    if(0 == xh_elf_gnu_hash_lookup_def(self, symbol, symidx)) return 0;
    if(0 == xh_elf_gnu_hash_lookup_undef(self, symbol, symidx)) return 0;
    return XH_ERRNO_NOTFND;
}

static int xh_elf_find_symidx_by_name(xh_elf_t *self, const char *symbol, uint32_t *symidx)
{
    if(self->is_use_gnu_hash) return xh_elf_gnu_hash_lookup(self, symbol, symidx);
    else return xh_elf_hash_lookup(self, symbol, symidx);
}

static int xh_elf_replace_function(xh_elf_t *self, const char *symbol, ElfW(Addr) addr, void *new_func, void **old_func)
{
    void *old_addr;
    unsigned int old_prot = 0;
    unsigned int need_prot = PROT_READ | PROT_WRITE;
    int r;
    if(*(void **)addr == new_func) return 0;
    if(0 != (r = xh_util_get_addr_protect(addr, self->pathname, &old_prot))) return r;
    if(old_prot != need_prot)
        if(0 != (r = xh_util_set_addr_protect(addr, need_prot))) return r;
    old_addr = *(void **)addr;
    if(NULL != old_func) *old_func = old_addr;
    *(void **)addr = new_func;
    if(old_prot != need_prot) xh_util_set_addr_protect(addr, old_prot);
    xh_util_flush_instruction_cache(addr);
    return 0;
}

static int xh_elf_check(xh_elf_t *self)
{
    if(0 == self->base_addr || 0 == self->bias_addr) return 1;
    if(NULL == self->ehdr || NULL == self->phdr || NULL == self->strtab || NULL == self->symtab) return 1;
    if(NULL == self->bucket || NULL == self->chain) return 1;
    if(1 == self->is_use_gnu_hash && NULL == self->bloom) return 1;
    return 0;
}

int xh_elf_init(xh_elf_t *self, uintptr_t base_addr, const char *pathname)
{
    if(0 == base_addr || NULL == pathname) return XH_ERRNO_INVAL;
    memset(self, 0, sizeof(xh_elf_t));
    self->pathname = pathname;
    self->base_addr = (ElfW(Addr))base_addr;
    self->ehdr = (ElfW(Ehdr) *)base_addr;
    self->phdr = (ElfW(Phdr) *)(base_addr + self->ehdr->e_phoff);

    ElfW(Phdr) *phdr0 = xh_elf_get_first_segment_by_type_offset(self, PT_LOAD, 0);
    if(NULL == phdr0) return XH_ERRNO_FORMAT;
    if(self->base_addr < phdr0->p_vaddr) return XH_ERRNO_FORMAT;
    self->bias_addr = self->base_addr - phdr0->p_vaddr;

    ElfW(Phdr) *dhdr = xh_elf_get_first_segment_by_type(self, PT_DYNAMIC);
    if(NULL == dhdr) return XH_ERRNO_FORMAT;

    self->dyn = (ElfW(Dyn) *)(self->bias_addr + dhdr->p_vaddr);
    self->dyn_sz = dhdr->p_memsz;
    ElfW(Dyn) *dyn = self->dyn;
    ElfW(Dyn) *dyn_end = self->dyn + (self->dyn_sz / sizeof(ElfW(Dyn)));
    uint32_t *raw;
    for(; dyn < dyn_end; dyn++)
    {
        switch(dyn->d_tag)
        {
        case DT_NULL: dyn = dyn_end; break;
        case DT_STRTAB:
            self->strtab = (const char *)(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))(self->strtab) < self->base_addr) return XH_ERRNO_FORMAT;
            break;
        case DT_SYMTAB:
            self->symtab = (ElfW(Sym) *)(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))(self->symtab) < self->base_addr) return XH_ERRNO_FORMAT;
            break;
        case DT_PLTREL:
            self->is_use_rela = (dyn->d_un.d_val == DT_RELA ? 1 : 0);
            break;
        case DT_JMPREL:
            self->relplt = (ElfW(Addr))(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))(self->relplt) < self->base_addr) return XH_ERRNO_FORMAT;
            break;
        case DT_PLTRELSZ: self->relplt_sz = dyn->d_un.d_val; break;
        case DT_REL: case DT_RELA:
            self->reldyn = (ElfW(Addr))(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))(self->reldyn) < self->base_addr) return XH_ERRNO_FORMAT;
            break;
        case DT_RELSZ: case DT_RELASZ: self->reldyn_sz = dyn->d_un.d_val; break;
        case DT_ANDROID_REL: case DT_ANDROID_RELA:
            self->relandroid = (ElfW(Addr))(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))(self->relandroid) < self->base_addr) return XH_ERRNO_FORMAT;
            break;
        case DT_ANDROID_RELSZ: case DT_ANDROID_RELASZ: self->relandroid_sz = dyn->d_un.d_val; break;
        case DT_HASH:
            if(self->is_use_gnu_hash) continue;
            raw = (uint32_t *)(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))raw < self->base_addr) return XH_ERRNO_FORMAT;
            self->bucket_cnt = raw[0]; self->chain_cnt = raw[1];
            self->bucket = &raw[2]; self->chain = &(self->bucket[self->bucket_cnt]);
            break;
        case DT_GNU_HASH:
            raw = (uint32_t *)(self->bias_addr + dyn->d_un.d_ptr);
            if((ElfW(Addr))raw < self->base_addr) return XH_ERRNO_FORMAT;
            self->bucket_cnt = raw[0]; self->symoffset = raw[1];
            self->bloom_sz = raw[2]; self->bloom_shift = raw[3];
            self->bloom = (ElfW(Addr) *)(&raw[4]);
            self->bucket = (uint32_t *)(&(self->bloom[self->bloom_sz]));
            self->chain = (uint32_t *)(&(self->bucket[self->bucket_cnt]));
            self->is_use_gnu_hash = 1;
            break;
        default: break;
        }
    }
    if(0 != self->relandroid)
    {
        const char *rel = (const char *)self->relandroid;
        if(self->relandroid_sz < 4 || rel[0] != 'A' || rel[1] != 'P' || rel[2] != 'S' || rel[3] != '2')
            return XH_ERRNO_FORMAT;
        self->relandroid += 4; self->relandroid_sz -= 4;
    }
    if(0 != xh_elf_check(self)) return XH_ERRNO_FORMAT;
    return 0;
}

static int xh_elf_find_and_replace_func(xh_elf_t *self, const char *section, int is_plt,
                                        const char *symbol, void *new_func, void **old_func,
                                        uint32_t symidx, void *rel_common, int *found)
{
    size_t r_info, r_sym, r_type;
    ElfW(Addr) r_offset, addr;
    if(self->is_use_rela)
    {
        ElfW(Rela) *rela = (ElfW(Rela) *)rel_common;
        r_info = rela->r_info; r_offset = rela->r_offset;
    }
    else
    {
        ElfW(Rel) *rel = (ElfW(Rel) *)rel_common;
        r_info = rel->r_info; r_offset = rel->r_offset;
    }
    r_sym = XH_ELF_R_SYM(r_info);
    if(r_sym != symidx) return 0;
    r_type = XH_ELF_R_TYPE(r_info);
    if(is_plt && r_type != XH_ELF_R_GENERIC_JUMP_SLOT) return 0;
    if(!is_plt && (r_type != XH_ELF_R_GENERIC_GLOB_DAT && r_type != XH_ELF_R_GENERIC_ABS)) return 0;
    if(NULL != found) *found = 1;
    addr = self->bias_addr + r_offset;
    if(addr < self->base_addr) return XH_ERRNO_FORMAT;
    return xh_elf_replace_function(self, symbol, addr, new_func, old_func);
}

int xh_elf_hook(xh_elf_t *self, const char *symbol, void *new_func, void **old_func)
{
    uint32_t symidx;
    void *rel_common;
    xh_elf_plain_reloc_iterator_t plain_iter;
    xh_elf_packed_reloc_iterator_t packed_iter;
    int found, r;

    if(NULL == self->pathname || NULL == symbol || NULL == new_func) return XH_ERRNO_INVAL;
    if(0 != (r = xh_elf_find_symidx_by_name(self, symbol, &symidx))) return 0;

    if(0 != self->relplt)
    {
        xh_elf_plain_reloc_iterator_init(&plain_iter, self->relplt, self->relplt_sz, self->is_use_rela);
        while(NULL != (rel_common = xh_elf_plain_reloc_iterator_next(&plain_iter)))
        {
            if(0 != (r = xh_elf_find_and_replace_func(self, ".rel.plt", 1, symbol, new_func, old_func, symidx, rel_common, &found))) return r;
            if(found) break;
        }
    }
    if(0 != self->reldyn)
    {
        xh_elf_plain_reloc_iterator_init(&plain_iter, self->reldyn, self->reldyn_sz, self->is_use_rela);
        while(NULL != (rel_common = xh_elf_plain_reloc_iterator_next(&plain_iter)))
            if(0 != (r = xh_elf_find_and_replace_func(self, ".rel.dyn", 0, symbol, new_func, old_func, symidx, rel_common, NULL))) return r;
    }
    if(0 != self->relandroid)
    {
        xh_elf_packed_reloc_iterator_init(&packed_iter, self->relandroid, self->relandroid_sz, self->is_use_rela);
        while(NULL != (rel_common = xh_elf_packed_reloc_iterator_next(&packed_iter)))
            if(0 != (r = xh_elf_find_and_replace_func(self, ".rel.android", 0, symbol, new_func, old_func, symidx, rel_common, NULL))) return r;
    }
    return 0;
}
